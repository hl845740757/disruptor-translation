/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.util.Util;

/**
 * 单生产者的缓存行填充 避免 {@link SingleProducerSequencerFields#nextValue}、{@link SingleProducerSequencerFields#cachedValue}
 * 与无关对象产生伪共享。
 */
abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
	 * 已分配的空间序号缓存，因为是单线程的生产者，不存在竞争，因此采用普通的long变量
	 * 减少对volatile变量的读写操作。{@link #cursor}
	 *
	 * 会在{@link SingleProducerSequencer#tryNext(int)}
	 * {@link SingleProducerSequencer#next(int)}
	 * 两处真正分配空间的时候得到更新。
	 * Set to -1 as sequence starting point。
     */
    long nextValue = Sequence.INITIAL_VALUE;
	/**
	 * 网关序列的最小序号(最慢消费进度)，对上次遍历结果的缓存，减少遍历操作(遍历涉及大量volatile读)。
	 * 因为是单线程的生产者，数据无竞争，因此使用普通的long变量即可。
	 * {@link Util#getMinimumSequence(Sequence[], long)}
	 *
	 * 会在{@link SingleProducerSequencer#hasAvailableCapacity(int, boolean)}
	 * {@link SingleProducerSequencer#tryNext(int)}
	 * {@link SingleProducerSequencer#next(int)}
	 * 三处地方被更新，即只要查询了消费者的进度，就会对它进行缓存，只会变大不会变小.(消费者的进度不会倒退)
	 *
	 * 与nextValue的区别在于未分配空间时也可能会更新
	 */
	long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * 单线程的序号生成器
 *
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 *
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.</p>
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
	/**
	 * 缓冲行填充，保护 {@link SingleProducerSequencerFields#nextValue}、{@link SingleProducerSequencerFields#cachedValue}
	 */
    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

	/**
	 * 是否有足够的容量
	 * @param requiredCapacity 需要的容量
	 * @param doStore 是否写入到volatile进度信息中（是否存储）,是否需要volatile来保证可见性
	 *                确保之前的数据对消费者可见。
	 * @return
	 */
    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore)
    {
        long nextValue = this.nextValue;
        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            if (doStore)
            {
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }

        // 上次分配的序号的缓存(已分配到这里)
        long nextValue = this.nextValue;
		// 本次申请分配的序号
        long nextSequence = nextValue + n;
		// 包点？构成环路的点：环形缓冲区可能追尾的点 = 等于本次申请的序号-环形缓冲区大小
		// 如果该序号大于最慢消费者的进度，那么表示需要等待
		long wrapPoint = nextSequence - bufferSize;
		// 上次缓存的最小网关序号(消费最慢的消费者的进度)(会在多个地方更新)
        long cachedGatingSequence = this.cachedValue;

		// wrapPoint > cachedGatingSequence 表示生产者追上消费者产生环路，上次看见的序号缓存无效，还需要更多的空间
		// cachedGatingSequence > nextValue 表示消费者的进度大于生产者进度，nextValue无效，单生产者下好像不可能啊？

		if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
			// cachedGatingSequence > nextValue

			// 插入StoreLoad内存屏障/栅栏，保证可见性。允许消费者线程看见上一次发布的数据
			cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            // 如果末端的消费者们仍然没让出该插槽则等待。
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            // 缓存生产者们最新的消费进度。
			// (该值可能是大于wrapPoint的，那么如果下一次的wrapPoint还小于cachedValue则可以直接进行分配)
			// 比如：我可能需要一个插槽位置，结果突然直接消费者们让出来3个插槽位置
            this.cachedValue = minSequence;
        }

        // 这里只写了缓存，并未写volatile变量，即不保证所有消费
        this.nextValue = nextSequence;

        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        // 临时更新，并没有真正写入
        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;
        // 这里居然没缓存 cachedValue？
        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        this.nextValue = sequence;
    }

    /**
	 * 发布一个数据，这个sequence最终会等于{@link #nextValue}
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(long sequence)
    {
    	// 更新发布进度，使用的是set，并没有保证对其他线程立即可见
        cursor.set(sequence);
        // 唤醒阻塞的消费者们(事件处理器们)
        waitStrategy.signalAllWhenBlocking();
    }

    /**
	 * 发布数据，这个hi其实就是{@link #nextValue}
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        publish(hi);
    }

    /**
	 * 指定序号的数据是否准备好了
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        return sequence <= cursor.get();
    }

	/**
	 * 因为是单生产者，那么区间段内的数据都是发布的
	 * @param lowerBound
	 * @param availableSequence The sequence to scan to.看见的已发布的最大序号
	 *                          多生产者模式下，已发布的数据可能是不连续的，因此不能直接该序号进行消费。
	 *                          必须顺序的消费，不能跳跃
	 *
	 * @return
	 */
	@Override
	public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence;
    }
}
