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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.lmax.disruptor.util.Util;

/**
 * 抽象序号生成器，作为单生产者和多生产者序列号生成器的超类，实现一些公共的功能(添加删除gatingSequence)。
 *
 * Base class for the various sequencer types (single/multi).  Provides
 * common functionality like the management of gating sequences (add/remove) and
 * ownership of the current cursor.
 */
public abstract class AbstractSequencer implements Sequencer
{
	/**
	 * 原子方式更新 追踪的Sequences
	 */
    private static final AtomicReferenceFieldUpdater<AbstractSequencer, Sequence[]> SEQUENCE_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(AbstractSequencer.class, Sequence[].class, "gatingSequences");

	/**
	 * 序号生成器缓冲区大小(ringBuffer缓冲区大小)
	 */
	protected final int bufferSize;
	/**
	 * 消费者的等待策略。
	 * 因为SequenceBarrier由Sequencer创建，Barrier需要生产者的Sequence信息。
	 */
    protected final WaitStrategy waitStrategy;
	/**
	 * 生产者的序号序列，所有的生产者使用同一个序列。
	 * 我主要纳闷这里为啥一下改名叫cursor了，可能是做区分吧，代码里面的带cursor的都表示生产者们的Sequence。
	 *
	 * 消费者与生产者之间的可见性保证是通过volatile变量的读写来保证的。
	 * 因为Sequence中的value是递增的，那么只要看见的序号比我之前看见的大，那么这期间发布的数据对消费者来说都是可见的。
	 */
	protected final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
	/**
	 * 网关Sequences，表示序号生成器必须和这些Sequence满足约束。
	 * 只有当所有的gatingSequences让出下一个插槽后，生产者才能获取该插槽。
	 *
	 * 对于生产者来讲，它只需要关注消费链最末端的消费者的进度（因为它们的进度是最慢的）。
	 * 即：gatingSequences就是所有消费链末端的消费们所拥有的的Sequence。
	 *
	 * 类似{@link ProcessingSequenceBarrier#dependentSequence}
	 */
    protected volatile Sequence[] gatingSequences = new Sequence[0];

    /**
     * Create with the specified buffer size and wait strategy.
     *
     * @param bufferSize   The total number of entries, must be a positive power of 2.
     * @param waitStrategy The wait strategy used by this sequencer
     */
    public AbstractSequencer(int bufferSize, WaitStrategy waitStrategy)
    {
        if (bufferSize < 1)
        {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        if (Integer.bitCount(bufferSize) != 1)
        {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        this.bufferSize = bufferSize;
        this.waitStrategy = waitStrategy;
    }

    /**
	 * 获取生产者的生产进度(以发布的最大序号)
     * @see Sequencer#getCursor()
     */
    @Override
    public final long getCursor()
    {
        return cursor.get();
    }

    /**
	 * 获取缓冲区大小
     * @see Sequencer#getBufferSize()
     */
    @Override
    public final int getBufferSize()
    {
        return bufferSize;
    }

    /**
	 * 添加网关Sequence(末端消费者的消费进度)，这些消费者的进度需要被关心
     * @see Sequencer#addGatingSequences(Sequence...)
     */
    @Override
    public final void addGatingSequences(Sequence... gatingSequences)
    {
        SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
    }

    /**
	 * 移除消费者的进度信息，这些消费者的进度不再被关心
     * @see Sequencer#removeGatingSequence(Sequence)
     */
    @Override
    public boolean removeGatingSequence(Sequence sequence)
    {
        return SequenceGroups.removeSequence(this, SEQUENCE_UPDATER, sequence);
    }

    /**
	 * 获取所有序号中的最小序号(生产者+消费者)，这是一个支持测试的方法
     * @see Sequencer#getMinimumSequence()
     */
    @Override
    public long getMinimumSequence()
    {
        return Util.getMinimumSequence(gatingSequences, cursor.get());
    }

    /**
	 * 为这些消费者创建一个屏障，用于协调它们和生产者之间的进度
     * @see Sequencer#newBarrier(Sequence...)
     */
    @Override
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack)
    {
        return new ProcessingSequenceBarrier(this, waitStrategy, cursor, sequencesToTrack);
    }

    /**
	 * 创建一个数据轮询器,讲道理在启动之前创建(因为是 new Sequence())
	 *
     * Creates an event poller for this sequence that will use the supplied data provider and
     * gating sequences.
     *
     * @param dataProvider    The data source for users of this event poller
     * @param gatingSequences Sequence to be gated on.
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    @Override
    public <T> EventPoller<T> newPoller(DataProvider<T> dataProvider, Sequence... gatingSequences)
    {
        return EventPoller.newInstance(dataProvider, this, new Sequence(), cursor, gatingSequences);
    }

    @Override
    public String toString()
    {
        return "AbstractSequencer{" +
            "waitStrategy=" + waitStrategy +
            ", cursor=" + cursor +
            ", gatingSequences=" + Arrays.toString(gatingSequences) +
            '}';
    }
}