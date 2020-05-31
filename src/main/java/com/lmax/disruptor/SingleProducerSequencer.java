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
     * 已预分配的缓存，因为是单线程的生产者，不存在竞争，因此采用普通的long变量
     * 表示 {@link #cursor} +1 ~  nextValue 这段空间被预分配出去了，但是可能还未填充数据。
     * <p>
     * 会在真正分配空间时更新
     * {@link SingleProducerSequencer#tryNext(int)}
     * {@link SingleProducerSequencer#next(int)}
     * <p>
     * 这个名字其实有点坑爹，其实不是下一个value。
     *
     * Set to -1 as sequence starting point
     */
    long nextValue = Sequence.INITIAL_VALUE;
    /**
     * 网关序列的最小序号缓存。
     * 因为是单线程的生产者，数据无竞争，因此使用普通的long变量即可。
     *
     * 在运行期间不调用{@link #claim(long)}的情况下：
     * 1.该缓存值是单调递增的，只会变大不会变小 2. cachedValue <= nextValue
     * 如果在运行期间调用了{@link #claim(long)}
     * 可能造成cachedValue > nextValue
     *
     * <p>
     * Q: 该缓存值的作用？
     * A: 除了直观上的减少对{@link #gatingSequences}的遍历产生的volatile读以外，还可以提高缓存命中率。
     * <p>
     * 由于消费者的{@link Sequence}变更较为频繁，因此消费者的{@link Sequence}的缓存极易失效。
     * 如果生产者频繁读取消费者的{@link Sequence}，极易遇见缓存失效问题（伪共享），从而影响性能。
     * 通过缓存一个值（在必要的时候更新），可以极大的减少对消费者的{@link Sequence}的读操作，从而提高性能。
     * PS: 使用一个变化频率较低的值代替一个变化频率较高的值，提高读效率。
     *
     * 在每次查询消费者的进度后，就会对它进行缓存
     * 会在{@link SingleProducerSequencer#hasAvailableCapacity(int, boolean)}
     * {@link SingleProducerSequencer#tryNext(int)}
     * {@link SingleProducerSequencer#next(int)}
     *
     * {@link Util#getMinimumSequence(Sequence[], long)}
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
	 * 注释可参考{@link #next(int)}
	 * @param requiredCapacity 需要的容量
	 * @param doStore 是否写入到volatile进度信息中（是否存储），是否需要volatile来保证可见性
	 *                确保之前的数据对消费者可见。
	 * @return
	 */
    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore)
    {
    	// 已分配序号缓存
        long nextValue = this.nextValue;

        // 可能构成环路的点：环形缓冲区可能追尾的点 = 等于本次申请的序号-环形缓冲区大小
		// 如果该序号大于最慢消费者的进度，那么表示追尾了，需要等待
        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        // 消费者的最慢进度
        long cachedGatingSequence = this.cachedValue;

		// wrapPoint > cachedGatingSequence 表示生产者追上消费者产生环路(追尾)，还需要更多的空间，上次看见的序号缓存无效，
		// cachedGatingSequence > nextValue 表示消费者的进度大于生产者进度，正常情况下不可能，
		// 但是在运行期间调用过 claim(long)方法则可能产生该情况，也表示缓存无效，需要重新判断。（建议忽略）
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
			// 因为publish使用的是set()/putOrderedLong，并不保证消费者能及时看见发布的数据，
            // 当我再次申请更多的空间时，必须保证消费者能消费发布的数据（那么就需要进度对消费者立即可见，使用volatile写即可）
            // 插入StoreLoad内存屏障/栅栏，确保立即的可见性。
            if (doStore)
            {
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            // 获取最新的消费者进度并缓存起来
            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            // 根据最新的消费者进度，仍然形成环路(产生追尾)，则表示空间不足
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
	 * 返回条件：
	 * 成功申请到空间(空间不足等待消费者消费)。
	 *
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        // 已分配的序号的缓存(已分配到这里)，初始-1
        long nextValue = this.nextValue;

		// 本次申请分配的序号
        long nextSequence = nextValue + n;

        // 构成环路的点：环形缓冲区可能追尾的点 = 等于本次申请的序号-环形缓冲区大小
        // 如果该序号大于最慢消费者的进度，那么表示追尾了，需要等待
        long wrapPoint = nextSequence - bufferSize;

		// 上次缓存的最小网关序号(消费最慢的消费者的进度)
        long cachedGatingSequence = this.cachedValue;

        // wrapPoint > cachedGatingSequence 表示生产者追上消费者产生环路(追尾)，即缓冲区已满，此时需要获取消费者们最新的进度，以确定是否队列满
        // cachedGatingSequence > nextValue 表示消费者的进度大于生产者进度，nextValue无效，建议忽略，
        // 正常情况下不会出现，调用claim(long)方法可能产生该情况，claim可能导致bug，只用在测试
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            // 插入StoreLoad内存屏障/栅栏，保证可见性。
            // 因为publish使用的是set()/putOrderedLong，并不保证其他消费者能及时看见发布的数据
            // 当我再次申请更多的空间时，必须保证消费者能消费发布的数据
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            // 如果末端的消费者们仍然没让出该插槽则等待，直到消费者们让出该插槽
            // 注意：这是导致死锁的重要原因！
            // 死锁分析：如果消费者挂掉了，而它的sequence没有从gatingSequences中删除的话，则生产者会死锁，它永远等不到消费者更新。
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            // 缓存生产者们最新的消费进度。
            // (该值可能是大于wrapPoint的，那么如果下一次的 wrapPoint小于等于cachedValue则可以直接进行分配)
            // 比如：我可能需要一个插槽位置，结果突然直接消费者们让出来3个插槽位置
            this.cachedValue = minSequence;
        }

        // 这里只写了缓存，并未写volatile变量，因为只是预分配了空间但是并未被发布数据，不需要让其他消费者感知到。
        // 消费者只会感知到真正被发布的序号
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

        // this.nextValue += n 更新已分配空间序号缓存
		// 这段空间已申请下来，但是还未发布(未填充数据)
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
    	// 单生产者模式下，预分配空间是操作的 nextValue,因此修改nextValue即可
		// 这里可能导致 nextValue < cachedValue
        // 这里没有更新 cursor （可能导致bug）
        this.nextValue = sequence;
    }

    /**
	 * 发布一个数据，这个sequence最终会等于{@link #nextValue}
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(long sequence)
    {
    	// 更新发布进度，使用的是set（putOrderedLong），并没有保证对其他线程立即可见(最终会看见)
		// 在下一次申请更多的空间时，如果发现需要消费者加快消费，则必须保证数据对消费者可见
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
     * @param availableSequence The sequence to scan to.看见的已发布的最大序号
     *                          多生产者模式下，已发布的数据可能是不连续的，因此不能直接该序号进行消费。
     *                          必须顺序的消费，不能跳跃
     */
    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence;
    }
}
