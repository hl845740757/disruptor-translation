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
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.*;

import java.util.*;

/**
 * 消费者信息仓库，将EventHandler 与 EventProcessor关联起来。
 * 传递给Disruptor的每一个EventHandler最终都会关联到一个EventProcessor。
 * <P>
 * 消费者之间的可见性保证：
 * Sequence是单调递增的，当看见前驱消费者的进度增大时，所有前驱消费者对区间段内的数据的处理对当前消费者来说都是可见的。
 * volatile的happens-before原则-----前驱消费者们的进度变大(写volatile)先于我看见它变大(读volatile)。
 *
 * Provides a repository mechanism to associate {@link EventHandler}s with {@link EventProcessor}s
 * @param <T> the type of the {@link EventHandler}
 */
class ConsumerRepository<T> implements Iterable<ConsumerInfo>
{
    /**
     * EventHandler到批量事件处理消费者信息的映射，用于信息查询
     */
    private final Map<EventHandler<?>, EventProcessorInfo<T>> eventProcessorInfoByEventHandler =
        new IdentityHashMap<>();
    /**
     * Sequence到消费者信息的映射
     * 一个消费者可能有多个Sequence{@link WorkerPool}，但是一个Sequence只从属一个消费者。
     */
    private final Map<Sequence, ConsumerInfo> eventProcessorInfoBySequence =
        new IdentityHashMap<>();
    /**
     * 消费者信息列表
     */
    private final Collection<ConsumerInfo> consumerInfos = new ArrayList<>();

    /**
     * 添加一个单事件处理器的消费者(单线程的消费者)
     */
    public void add(
        final EventProcessor eventprocessor,
        final EventHandler<? super T> handler,
        final SequenceBarrier barrier)
    {
        final EventProcessorInfo<T> consumerInfo = new EventProcessorInfo<>(eventprocessor, handler, barrier);
        eventProcessorInfoByEventHandler.put(handler, consumerInfo);
        eventProcessorInfoBySequence.put(eventprocessor.getSequence(), consumerInfo);
        consumerInfos.add(consumerInfo);
    }

    /**
     * 添加一个单事件处理器的消费者
     */
    public void add(final EventProcessor processor)
    {
        final EventProcessorInfo<T> consumerInfo = new EventProcessorInfo<>(processor, null, null);
        eventProcessorInfoBySequence.put(processor.getSequence(), consumerInfo);
        consumerInfos.add(consumerInfo);
    }

    /**
     * 添加一个多事件处理器的消费者(WorkerPool代表一个多线程的消费者)
     */
    public void add(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier)
    {
        final WorkerPoolInfo<T> workerPoolInfo = new WorkerPoolInfo<>(workerPool, sequenceBarrier);
        consumerInfos.add(workerPoolInfo);
        for (Sequence sequence : workerPool.getWorkerSequences())
        {
            eventProcessorInfoBySequence.put(sequence, workerPoolInfo);
        }
    }

    /**
     * 获取消费链末端的消费者的Sequence
     * @param includeStopped 是否包含已停止运行的消费者
     */
    public Sequence[] getLastSequenceInChain(boolean includeStopped)
    {
        List<Sequence> lastSequence = new ArrayList<>();
        for (ConsumerInfo consumerInfo : consumerInfos)
        {
            if ((includeStopped || consumerInfo.isRunning()) && consumerInfo.isEndOfChain())
            {
                final Sequence[] sequences = consumerInfo.getSequences();
                Collections.addAll(lastSequence, sequences);
            }
        }

        return lastSequence.toArray(new Sequence[lastSequence.size()]);
    }

    /**
     * 获取EventHandler绑定的EventProcessor
     * （每一个EventHandler都会被包装为一个EventProcessor，每一个EventProcessor有自己独立的Sequence）
     */
    public EventProcessor getEventProcessorFor(final EventHandler<T> handler)
    {
        final EventProcessorInfo<T> eventprocessorInfo = getEventProcessorInfo(handler);
        if (eventprocessorInfo == null)
        {
            throw new IllegalArgumentException("The event handler " + handler + " is not processing events.");
        }

        return eventprocessorInfo.getEventProcessor();
    }

    /**
     * 获取EventHandler绑定的Sequence
     * （每一个EventHandler都会被包装为一个EventProcessor,每一个EventProcessor有自己独立的Sequence）
     */
    public Sequence getSequenceFor(final EventHandler<T> handler)
    {
        return getEventProcessorFor(handler).getSequence();
    }

    /**
     * 将这些Sequence标记为不在消费者链的末端了
     */
    public void unMarkEventProcessorsAsEndOfChain(final Sequence... barrierEventProcessors)
    {
        for (Sequence barrierEventProcessor : barrierEventProcessors)
        {
            getEventProcessorInfo(barrierEventProcessor).markAsUsedInBarrier();
        }
    }

    @Override
    public Iterator<ConsumerInfo> iterator()
    {
        return consumerInfos.iterator();
    }

    /**
     * 获取EventHandler绑定的SequenceBarrier
     * （每一个EventHandler都会被包装为一个EventProcessor,每一个EventProcessor从属于一个消费者，
     *  一个消费者有且仅有一个SequenceBarrier)
     */
    public SequenceBarrier getBarrierFor(final EventHandler<T> handler)
    {
        final ConsumerInfo consumerInfo = getEventProcessorInfo(handler);
        return consumerInfo != null ? consumerInfo.getBarrier() : null;
    }

    /**
     * 如果EventHandler是独立工作的，那么可以获取它的EventProcessorInfo
     * 独立工作的EventHandler会被包装为一个独立的消费者{@link BatchEventProcessor}，
     * BatchEventProcessor消费者对应的消费者信息就是 {@link EventProcessorInfo}
     */
    private EventProcessorInfo<T> getEventProcessorInfo(final EventHandler<T> handler)
    {
        return eventProcessorInfoByEventHandler.get(handler);
    }

    /**
     * 获取Sequence对应的消费者信息，一个消费者可能有多个Sequence，但是一个Sequence一定只从属于一个消费者
     */
    private ConsumerInfo getEventProcessorInfo(final Sequence barrierEventProcessor)
    {
        return eventProcessorInfoBySequence.get(barrierEventProcessor);
    }
}
