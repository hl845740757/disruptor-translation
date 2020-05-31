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

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WorkHandler;

import java.util.Arrays;

/**
 * 事件处理器组，作为Disruptor构成中的一部分。
 * <b>这个类很重要</b>
 *
 * 用于组织消费者之间的依赖关系。
 * 建立消费者之间的依赖其实也就是建立消费者与前驱节点的Sequence之间的依赖
 * 它会建立依赖消费者之间的依赖，也就是Barrier中的dependentSequence的由来。
 * {@link com.lmax.disruptor.ProcessingSequenceBarrier#dependentSequence}
 *
 * 这个类如果不研究一下，很多地方阅读可能有障碍。
 *
 * A group of {@link EventProcessor}s used as part of the {@link Disruptor}.
 *
 * @param <T> the type of entry used by the event processors.
 */
public class EventHandlerGroup<T>
{
	/**
	 * 方便回调，用于创建具有依赖关系的消费者
	 * {@link Disruptor#createEventProcessors(Sequence[], EventHandler[])}
	 * {@link Disruptor#createEventProcessors(Sequence[], EventProcessorFactory[])}
	 *
	 */
    private final Disruptor<T> disruptor;
	/**
	 * 所有消费者的信息，方便增加和查询
	 * (包含所有EventHandler的信息)
	 */
    private final ConsumerRepository<T> consumerRepository;
	/**
	 * 当前EventHandlerGroup拥有的所有Sequence。
	 * 也隐含的表示了EventHandlerGroup所代表的所有消费者。
	 * 也就是EventHandlerGroup的所有EventHandler的后继消费者的依赖Sequences
	 * 其它代码里面的 dependentSequence / barrierSequences / sequencesToTrack 其实就是它啦。
	 */
    private final Sequence[] sequences;

    EventHandlerGroup(
        final Disruptor<T> disruptor,
        final ConsumerRepository<T> consumerRepository,
        final Sequence[] sequences)
    {
        this.disruptor = disruptor;
        this.consumerRepository = consumerRepository;
        this.sequences = Arrays.copyOf(sequences, sequences.length);
    }

    /**
	 * 创建一个合并了给定group的新的EventHandlerGroup。
	 * 用于建立消费者与合并后的group的sequence的依赖。
     * Create a new event handler group that combines the consumers in this group with <code>otherHandlerGroup</code>.
     *
     * @param otherHandlerGroup the event handler group to combine.
     * @return a new EventHandlerGroup combining the existing and new consumers into a single dependency group.
     */
    public EventHandlerGroup<T> and(final EventHandlerGroup<T> otherHandlerGroup)
    {
        final Sequence[] combinedSequences = new Sequence[this.sequences.length + otherHandlerGroup.sequences.length];
        System.arraycopy(this.sequences, 0, combinedSequences, 0, this.sequences.length);
        System.arraycopy(
            otherHandlerGroup.sequences, 0,
            combinedSequences, this.sequences.length, otherHandlerGroup.sequences.length);
        return new EventHandlerGroup<>(disruptor, consumerRepository, combinedSequences);
    }

    /**
     * Create a new event handler group that combines the handlers in this group with <code>processors</code>.
     *
     * @param processors the processors to combine.
     * @return a new EventHandlerGroup combining the existing and new processors into a single dependency group.
     */
    public EventHandlerGroup<T> and(final EventProcessor... processors)
    {
        Sequence[] combinedSequences = new Sequence[sequences.length + processors.length];

        for (int i = 0; i < processors.length; i++)
        {
            consumerRepository.add(processors[i]);
            combinedSequences[i] = processors[i].getSequence();
        }
        System.arraycopy(sequences, 0, combinedSequences, processors.length, sequences.length);

        return new EventHandlerGroup<>(disruptor, consumerRepository, combinedSequences);
    }

    /**
	 * 添加一批EventHandler从RingBuffer中消费事件。
	 *
	 * 这些新增的EventHandler只能消费已经被当前EventHandlerGroup代表的所有消费者已经消费的事件。
	 *
	 * 这个方法对于构建链式消费来说很有用，会确立明确的先后顺序。
	 *
     * <p>Set up batch handlers to consume events from the ring buffer. These handlers will only process events
     * after every {@link EventProcessor} in this group has processed the event.</p>
     *
     * <p>This method is generally used as part of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     *
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * @param handlers the batch handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to set up a event processor barrier over the created event processors.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> then(final EventHandler<? super T>... handlers)
    {
        return handleEventsWith(handlers);
    }

    /**
	 * 利用EventProcessorFactory添加一批EventProcessor从RingBuffer中消费数据。
	 * 这些新增的Processor只能消费已经被当前EventHandlerGroup代表的所有消费者已经消费的事件。
	 *
	 * 这个方法对于构建链式消费来说很有用，会确立明确的先后顺序。
	 *
	 * 和{@link #then(EventHandler[])}的区别就是 EventHandler会被保证为BatchEventProcessor，而这里
	 * 的Processor是由给定的工厂创建的。
	 *
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start these processors when {@link Disruptor#start()} is called.</p>
     *
     * <p>This method is generally used as part of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     *
     * @param eventProcessorFactories the event processor factories to use to create the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> then(final EventProcessorFactory<T>... eventProcessorFactories)
    {
        return handleEventsWith(eventProcessorFactories);
    }

    /**
	 * 创建一个WorkerPool消费从RingBuffer中消费事件。
	 * WorkerPool只会消费已经被当前EventHandlerGroup所代表的所有消费者已经消费的事件。
	 * 且每一个事件只会被WokerPool中的一个WorkHandler处理。
	 *
	 * 这个方法对于构建链式消费来说很有用，会确立明确的先后顺序。
	 *
     * <p>Set up a worker pool to handle events from the ring buffer. The worker pool will only process events
     * after every {@link EventProcessor} in this group has processed the event. Each event will be processed
     * by one of the work handler instances.</p>
     *
     * <p>This method is generally used as part of a chain. For example if the handler <code>A</code> must
     * process events before the worker pool with handlers <code>B, C</code>:</p>
     *
     * <pre><code>dw.handleEventsWith(A).thenHandleEventsWithWorkerPool(B, C);</code></pre>
     *
     * @param handlers the work handlers that will process events. Each work handler instance will provide an extra thread in the worker pool.
     * @return a {@link EventHandlerGroup} that can be used to set up a event processor barrier over the created event processors.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> thenHandleEventsWithWorkerPool(final WorkHandler<? super T>... handlers)
    {
        return handleEventsWithWorkerPool(handlers);
    }

    /**
	 * 添加一批EventHandler从RingBuffer中消费事件。
	 *
	 * 这些新增的EventHandler只能消费已经被当前EventHandlerGroup代表的所有消费者已经消费的事件。
	 *
	 * 这个方法对于构建链式消费来说很有用，会确立明确的先后顺序。
	 *
     * <p>Set up batch handlers to handle events from the ring buffer. These handlers will only process events
     * after every {@link EventProcessor} in this group has processed the event.</p>
     *
     * <p>This method is generally used as part of a chain. For example if <code>A</code> must
     * process events before <code>B</code>:</p>
     *
     * <pre><code>dw.after(A).handleEventsWith(B);</code></pre>
     *
     * @param handlers the batch handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to set up a event processor barrier over the created event processors.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers)
    {
        return disruptor.createEventProcessors(sequences, handlers);
    }

    /**
	 *
	 * 利用EventProcessorFactory添加一批EventProcessor从RingBuffer中消费数据。
	 * 这些新增的Processor只能消费已经被当前EventHandlerGroup代表的所有消费者已经消费的事件。
	 *
	 * 这个方法对于构建链式消费来说很有用，会确立明确的先后顺序。
	 *
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start these processors when {@link Disruptor#start()} is called.</p>
     *
     * <p>This method is generally used as part of a chain. For example if <code>A</code> must
     * process events before <code>B</code>:</p>
     *
     * <pre><code>dw.after(A).handleEventsWith(B);</code></pre>
     *
     * @param eventProcessorFactories the event processor factories to use to create the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories)
    {
        return disruptor.createEventProcessors(sequences, eventProcessorFactories);
    }

    /**
	 * 创建一个WorkerPool消费从RingBuffer中消费事件。
	 * WorkerPool只会消费已经被当前EventHandlerGroup所代表的所有消费者已经消费的事件。
	 * 且每一个事件只会被WokerPool中的一个WorkHandler处理。
	 *
	 * 这个方法对于构建链式消费来说很有用，会确立明确的先后顺序。
	 *
     * <p>Set up a worker pool to handle events from the ring buffer. The worker pool will only process events
     * after every {@link EventProcessor} in this group has processed the event. Each event will be processed
     * by one of the work handler instances.</p>
     *
     * <p>This method is generally used as part of a chain. For example if the handler <code>A</code> must
     * process events before the worker pool with handlers <code>B, C</code>:</p>
     *
     * <pre><code>dw.after(A).handleEventsWithWorkerPool(B, C);</code></pre>
     *
     * @param handlers the work handlers that will process events. Each work handler instance will provide an extra thread in the worker pool.
     * @return a {@link EventHandlerGroup} that can be used to set up a event processor barrier over the created event processors.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWithWorkerPool(final WorkHandler<? super T>... handlers)
    {
        return disruptor.createWorkerPool(sequences, handlers);
    }

    /**
     * Create a dependency barrier for the processors in this group.
     * This allows custom event processors to have dependencies on
     * {@link com.lmax.disruptor.BatchEventProcessor}s created by the disruptor.
     *
     * @return a {@link SequenceBarrier} including all the processors in this group.
     */
    public SequenceBarrier asSequenceBarrier()
    {
        return disruptor.getRingBuffer().newBarrier(sequences);
    }
}
