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

import sun.misc.Unsafe;

import com.lmax.disruptor.util.Util;


/**
 * 多生产者模型下的序号生成器
 * 注意:
 * 在使用该序号生成器时，调用{@link Sequencer#getCursor()}后必须 调用{@link Sequencer#getHighestPublishedSequence(long, long)}
 * 确定真正可用的序号。（因为多生产者模型下，生产者之间是无锁的，预分配空间，那么真正填充的数据可能是非连续的），因此需要确认
 *
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Suitable for use for sequencing across multiple publisher threads.</p>
 *
 * <p> * Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#next()}, to determine the highest available sequence that can be read, then
 * {@link Sequencer#getHighestPublishedSequence(long, long)} should be used.</p>
 */
public final class MultiProducerSequencer extends AbstractSequencer
{
    private static final Unsafe UNSAFE = Util.getUnsafe();
    // 获取数组对象头元素偏移量
    private static final long BASE = UNSAFE.arrayBaseOffset(int[].class);
    // 数组一个元素的地址偏移量(用于计算指定下标的元素的内存地址)
    private static final long SCALE = UNSAFE.arrayIndexScale(int[].class);
	/**
	 * 上次获取到的最小序号缓存，会被并发的访问，因此用Sequence，而单线程的Sequencer中则使用了一个普通long变量。
	 * 在任何时候查询了消费者进度信息时都需要更新它。
	 * 某些时候可以减少{@link #gatingSequences}的遍历(减少volatile读操作)。
	 *
	 * Util.getMinimumSequence(gatingSequences, current)的查询结果是递增的，但是缓存结果不一定的是递增，变量的更新存在竞态条件，
	 * 它可能会被设置为一个更小的值。
	 *
	 * gatingSequenceCache 的更新采用的都是set,因为本身就可能设置为一个错误的值(更小的值)，使用volatile写也无法解决该问题，
	 * 使用set可以减少内存屏障消耗
	 * {@link SingleProducerSequencerFields#cachedValue}
	 */
    private final Sequence gatingSequenceCache = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    // 多生产者模式下，标记哪些序号是真正被填充了数据的。 (用于获取连续的可用空间)
    // availableBuffer tracks the state of each ringbuffer slot
    // see below for more details on the approach
    private final int[] availableBuffer;
	/**
	 * 用于快速的计算序号对应的下标，与计算就可以，本质上和RingBuffer中计算插槽位置一样
	 * {@link RingBufferFields#elementAt(long)}
	 */
    private final int indexMask;

	/**
	 * 用于计算sequence可用标记的偏移量(与计算)
	 */
	private final int indexShift;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public MultiProducerSequencer(int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
        availableBuffer = new int[bufferSize];
        indexMask = bufferSize - 1;
        indexShift = Util.log2(bufferSize);

        // 初始化时，设置插槽上的所有标记为不可用
        initialiseAvailableBuffer();
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(gatingSequences, requiredCapacity, cursor.get());
    }

	/**
	 * 是否有足够的空间，多线程下返回的总是一个‘旧’值，不一定具有价值
	 *
	 * 总体思路:
	 * 可能构成环路的点 如果 大于消费者的消费进度，则表示会发生追尾，空间不足
	 *
	 * @param gatingSequences 网关序列们
	 * @param requiredCapacity 需要的空间
	 * @param cursorValue 当前看见的生产者进度
	 * @return
	 */
    private boolean hasAvailableCapacity(Sequence[] gatingSequences, final int requiredCapacity, long cursorValue)
    {
    	// 需要预分配这一段空间 cursorValue+1 ~ cursorValue+requiredCapacity这一段
    	// 可能构成环路的点/环形缓冲区可能追尾的点 = 请求的序号 - 环形缓冲区大小
        long wrapPoint = (cursorValue + requiredCapacity) - bufferSize;
        // 缓存的消费者们的最慢进度值，小于等于真实进度
		// (对单个线程来说可能看见一个比该线程上次看见的更小的值/对另一个线程来说就可能看见一个比生产进度更大的值)
        long cachedGatingSequence = gatingSequenceCache.get();

		// 1.wrapPoint > cachedGatingSequence 表示生产者追上消费者产生环路，上次看见的序号缓存无效，还需要更多的空间
		// 2.cachedGatingSequence > nextValue 表示消费者的进度大于当前生产者进度，current无效，
		// 当其它生产者竞争成功，发布的数据也被消费者消费了时可能产生。(如2生产者1个消费者)
		// 无锁算法(CAS)都是比较烧脑的算法，尽量不要自己设计，交给大师们设计。
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > cursorValue)
        {
			// 缓存无效，尝试更新一下缓存
			long minSequence = Util.getMinimumSequence(gatingSequences, cursorValue);
			// 这里存在竞态条件，多线程模式下，可能会被设置为多个线程看见的结果中的任意以一个。
			// 可能比 cachedGatingSequence更小，可能比cursorValue更大
            gatingSequenceCache.set(minSequence);

            // 是否产生环路/追尾
            if (wrapPoint > minSequence)
            {
                return false;
            }
        }
        // 看见有足够的空间
        return true;
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        cursor.set(sequence);
    }

    /**
	 * 算法注释可参考 {@link #hasAvailableCapacity(Sequence[], int, long)}
	 * 和 {@link #tryNext(int)}
	 * 有细微区别
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
	 * 返回条件：成功申请到空间才会返回。
	 *
	 * 总体思路：
	 * 1.空间不足就继续等待。
	 * 2.空间足够时尝试CAS竞争空间。
	 * 3.竞争成功则返回，竞争失败则重试。
	 *
	 *
	 *  如果不使用缓存的话可能是这样
	 *  long current;
	 *  long next;
	 *        while (true){
	 *			current = cursor.get();
	 *			next = current + n;
	 *			long wrapPoint = next - bufferSize;
	 *			long gatingSequence = Util.getMinimumSequence(gatingSequences, current);
	 *			if (wrapPoint > gatingSequence || gatingSequence > current){
	 *				continue;
	 *			}
	 *			if (cursor.compareAndSet(current,next)){
	 *				break;
	 *			}
	 *		}
	 *	return next;
	 *
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }

        long current;
        long next;

		// 使用缓存导致太复杂
        do
        {
            current = cursor.get();
            next = current + n;

            // 可能构成环路的点/环形缓冲区可能追尾的点 = 请求的序号 - 环形缓冲区大小
            long wrapPoint = next - bufferSize;
            // 缓存的消费者们的最慢进度值，小于等于真实进度
            long cachedGatingSequence = gatingSequenceCache.get();

            // 第一步：空间不足就继续等待。
			// wrapPoint > cachedGatingSequence 表示生产者追上消费者产生环路，上次看见的序号缓存无效，还需要更多的空间
			// cachedGatingSequence > nextValue 表示消费者的进度大于当前生产者进度，current无效，
			// 当其它生产者竞争成功，发布的数据也被消费者消费了时可能产生。(如2生产者1个消费者)
			// 无锁算法(CAS)都是比较烧脑的算法，尽量不要自己设计，交给大师们设计。
			if (wrapPoint > cachedGatingSequence || cachedGatingSequence > current)
            {
            	// 走进这里表示当前缓存对我来说没有帮助，尝试获取最新的消费者进度
                long gatingSequence = Util.getMinimumSequence(gatingSequences, current);

                // 消费者最新的进度仍然与我构成了环路，那么只能重试，减少不必要的缓存更新
                if (wrapPoint > gatingSequence)
                {
					// 这里和查询是否用足够空间的区别，会停顿一下生产者，减少竞争程度
					LockSupport.parkNanos(1); // TODO, should we spin based on the wait strategy?
                    continue;
                }

                // 检测到未构成环路(多线程下这都是假设条件)，更新网关序列，然后进行重试
                // 这里存在竞态条件，多线程模式下，可能会被设置为一个更小的值，从而小于当前分配的值(current)
                gatingSequenceCache.set(gatingSequence);

                // 这里看见有足够空间，这里如果尝试竞争空间会产生重复的代码，其实就是外层的代码，因此直接等待下一个循环
            }
            // 第二步：看见空间足够时尝试CAS竞争空间
            else if (cursor.compareAndSet(current, next))
            {
            	// 第三步：成功竞争到了这片空间，返回
            	// 注意！这里更新了生产者进度，然而生产者并未真正发布数据。
				// 因此需要调用getHighestPublishedSequence()确认真正的可用空间
                break;
            }
            // 第三步：竞争失败则重试
        }
        while (true);

        return next;
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
	 * 要搞清楚返回条件很重要：
	 * 要么看见空间不足，要么看见有足够空间且成功申请到空间。
	 *
	 * 总体思路：
	 * 1.查看是否有足空间
	 * 2.如果空间不足，则失败返回。 如果空间足够，则CAS竞争。
	 * 3.如果竞争成功，则返回，竞争失败则重试(竞争失败表示可能还有可用空间)。
	 *
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;

            // 看见空间不够时返回
            if (!hasAvailableCapacity(gatingSequences, n, current))
            {
                throw InsufficientCapacityException.INSTANCE;
            }
		}
        while (!cursor.compareAndSet(current, next));//看见有足够的空间，CAS竞争失败时重试

        return next;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
    	// 这里居然也没更新gatingSequenceCache。
        long consumed = Util.getMinimumSequence(gatingSequences, cursor.get());
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }

	/**
	 * 初始化插槽上的标记为不可用
	 */
	private void initialiseAvailableBuffer()
    {
        for (int i = availableBuffer.length - 1; i != 0; i--)
        {
            setAvailableBufferValue(i, -1);
        }

        setAvailableBufferValue(0, -1);
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        setAvailable(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        for (long l = lo; l <= hi; l++)
        {
            setAvailable(l);
        }
        waitStrategy.signalAllWhenBlocking();
    }

    /**
	 * 设置目标插槽上的数据可用了，将对于插槽上的标记置位可用标记
	 *
     * The below methods work on the availableBuffer flag.
     * <p>
     * The prime reason is to avoid a shared sequence object between publisher threads.
     * (Keeping single pointers tracking start and end would require coordination
     * between the threads).
     * <p>
     * --  Firstly we have the constraint that the delta between the cursor and minimum
     * gating sequence will never be larger than the buffer size (the code in
     * next/tryNext in the Sequence takes care of that).
     * -- Given that; take the sequence value and mask off the lower portion of the
     * sequence as the index into the buffer (indexMask). (aka modulo operator)
     * -- The upper portion of the sequence becomes the value to check for availability.
     * ie: it tells us how many times around the ring buffer we've been (aka division)
     * -- Because we can't wrap without the gating sequences moving forward (i.e. the
     * minimum gating sequence is effectively our last available position in the
     * buffer), when we have new data and successfully claimed a slot we can simply
     * write over the top.
     */
    private void setAvailable(final long sequence)
    {
        setAvailableBufferValue(calculateIndex(sequence), calculateAvailabilityFlag(sequence));
    }

	private void setAvailableBufferValue(int index, int flag)
    {
        long bufferAddress = (index * SCALE) + BASE;
        UNSAFE.putOrderedInt(availableBuffer, bufferAddress, flag);
    }

    /**
	 * 当指定插槽上的标记和sequence算出的标记一致时，表示可用(已发布)
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        int index = calculateIndex(sequence);
        int flag = calculateAvailabilityFlag(sequence);
        long bufferAddress = (index * SCALE) + BASE;
        return UNSAFE.getIntVolatile(availableBuffer, bufferAddress) == flag;
    }


	/**
	 * 查询 nextSequence-availableSequence 区间段之间连续发布的最大序号。多生产者模式下可能是不连续的。
	 * 多生产者模式下{@link Sequencer#next(int)} next是预分配的，因此可能部分数据还未被填充。
	 *
	 * @param lowerBound 我期望消费的最小序号，前面的一定都已经发布了
	 * @param availableSequence The sequence to scan to.看见的已发布的最大序号
	 *                          多生产者模式下，已发布的数据可能是不连续的，因此不能直接该序号进行消费。
	 *                          必须顺序的消费，不能跳跃
	 * @return
	 */
	@Override
	public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++)
        {
        	// 这里中断了，不是连续发布的，需要剪断
            if (!isAvailable(sequence))
            {
                return sequence - 1;
            }
        }

        return availableSequence;
    }

	/**
	 * 计算sequence对应可用标记
	 * @param sequence
	 * @return
	 */
	private int calculateAvailabilityFlag(final long sequence)
    {
        return (int) (sequence >>> indexShift);
    }

	/**
	 * 计算sequence对应的下标(插槽位置)
	 * @param sequence
	 * @return
	 */
	private int calculateIndex(final long sequence)
    {
        return ((int) sequence) & indexMask;
    }
}
