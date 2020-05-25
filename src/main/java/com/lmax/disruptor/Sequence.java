/*
 * Copyright 2012 LMAX Ltd.
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

import sun.misc.Unsafe;

import com.lmax.disruptor.util.Util;


class LhsPadding
{
    protected long p1, p2, p3, p4, p5, p6, p7;
}

class Value extends LhsPadding
{
	/**
	 * 前后各填充7个字段，才能保证数据一定不被伪共享。
	 * 缓存行一般8字宽，64个字节，为8个long字段的宽度。
	 * 更多伪共享信息请查阅资料
	 */
    protected volatile long value;
}

class RhsPadding extends Value
{
    protected long p9, p10, p11, p12, p13, p14, p15;
}

/**
 * 序列，用于追踪RingBuffer和EventProcessor的进度，表示生产/消费进度。
 *
 * 大写的Sequence称之为序列，小写的sequence称之为序号。Sequencer称之为序号生成器。
 *
 * 1.通过CAS支持对一个数组的并发读写操作
 * 2.通过字段填充解决了CPU缓存行伪共享问题。
 *
 * <p>Concurrent sequence class used for tracking the progress of
 * the ring buffer and event processors.  Support a number
 * of concurrent operations including CAS and order writes.
 *
 * <p>Also attempts to be more efficient with regards to false
 * sharing by adding padding around the volatile field.
 */
public class Sequence extends RhsPadding
{
    static final long INITIAL_VALUE = -1L;
    private static final Unsafe UNSAFE;
    private static final long VALUE_OFFSET;

    static
    {
        UNSAFE = Util.getUnsafe();
        try
        {
            VALUE_OFFSET = UNSAFE.objectFieldOffset(Value.class.getDeclaredField("value"));
        }
        catch (final Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a sequence initialised to -1.
     */
    public Sequence()
    {
        this(INITIAL_VALUE);
    }

    /**
     * Create a sequence with a specified initial value.
     *
     * @param initialValue The initial value for this sequence.
     */
    public Sequence(final long initialValue)
    {
        // 这里使用Ordered模式写入就可以保证：对象的发布操作不会重排序到对象构造完成前（其它线程不会看见构造未完成的对象）。
        // 会比volatile开销低一些
        UNSAFE.putOrderedLong(this, VALUE_OFFSET, initialValue);
    }

    /**
     * Perform a volatile read of this sequence's value.
	 * value字段volatile读操作
     *
     * @return The current value of the sequence.
     */
    public long get()
    {
        return value;
    }

    /**
	 * 对sequence执行一个Ordered模式写操作,而不是volatile模式写操作。
	 * 目的是在当前写操作和之前的写操作直接插入一个StoreStore屏障,保证屏障之前的写操作先于当前写操作
	 * 对其他CPU可见。(减少内存屏障的消耗，StoreLoad屏障消耗较大)
     * <p>
     * 在J9中，对内存屏障的使用进行了规范，用{@code setRelease}代替了LazySet/putOrdered叫法。
     * （对应C++ 11中的Release Acquire模式）
	 *
	 * 更多内存屏障/内存栅栏信息请查阅资料。
     * （建议看一下J9的VarHandle类）
	 *
     * Perform an ordered write of this sequence.  The intent is
     * a Store/Store barrier between this write and any previous
     * store.
     *
     * @param value The new value for the sequence.
     */
    public void set(final long value)
    {
        UNSAFE.putOrderedLong(this, VALUE_OFFSET, value);
    }

    /**
	 * 执行一个volatile写操作。
	 * 目的是在当前写操作与之前的写操作之间插入一个StoreStore屏障，在当前写操作和
	 * 后续的任意volatile变量读操作之间插入一个StoreLoad屏障，保证当前的volatile写操作
	 * 对后续的volatile读操作立即可见。
	 *
	 * 更多内存屏障/内存栅栏信息请查阅资料。
	 *
     * Performs a volatile write of this sequence.  The intent is
     * a Store/Store barrier between this write and any previous
     * write and a Store/Load barrier between this write and any
     * subsequent volatile read.
     *
     * @param value The new value for the sequence.
     */
    public void setVolatile(final long value)
    {
        UNSAFE.putLongVolatile(this, VALUE_OFFSET, value);
    }

    /**
	 * CAS更新Sequence的value字段
     * Perform a compare and set operation on the sequence.
     *
     * @param expectedValue The expected current value.
     * @param newValue The value to update to.
     * @return true if the operation succeeds, false otherwise.
     */
    public boolean compareAndSet(final long expectedValue, final long newValue)
    {
        return UNSAFE.compareAndSwapLong(this, VALUE_OFFSET, expectedValue, newValue);
    }

    /**
	 * 原子方式加1，并返回+1后的值
     * Atomically increment the sequence by one.
     *
     * @return The value after the increment
     */
    public long incrementAndGet()
    {
        return addAndGet(1L);
    }

    /**
	 * 原子方式加上一个特定的值，并返回加完后的值
     * Atomically add the supplied value.
     *
     * @param increment The value to add to the sequence.
     * @return The value after the increment.
     */
    public long addAndGet(final long increment)
    {
        long currentValue;
        long newValue;

        do
        {
            currentValue = get();
            newValue = currentValue + increment;
        }
        while (!compareAndSet(currentValue, newValue));

        return newValue;
    }

    @Override
    public String toString()
    {
        return Long.toString(get());
    }
}
