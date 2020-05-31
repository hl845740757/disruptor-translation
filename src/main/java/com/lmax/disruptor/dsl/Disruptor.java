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

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.EventTranslatorThreeArg;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.WorkerPool;
import com.lmax.disruptor.util.Util;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.该类其实是个门面，用于帮助用户组织消费者。
 *
 * 2.{@link #handleEventsWith(EventHandler[])}
 *   {@link #handleEventsWith(EventProcessor...)}
 *   {@link #handleEventsWith(EventProcessorFactory[])}
 *   所有的 {@code handleEventsWith} 都是添加消费者，每一个{@link EventHandler}、
 *   {@link EventProcessor}、{@link EventProcessorFactory}都会被包装为一个独立的消费者{@link BatchEventProcessor}
 *   数组长度就代表了添加的消费者个数。
 *
 * 3.{@link #handleEventsWithWorkerPool(WorkHandler[])} 方法为添加一个多线程的消费者，这些handler共同构成一个消费者.
 *   （WorkHandler[]会被包装为 {@link WorkerPool}，一个WorkPool是一个消费者，WorkPool中的Handler们
 *   协作共同完成消费。一个事件只会被WokerPool中的某一个WorkHandler消费。
 *   数组的长度决定了线程池内的线程数量。
 *
 * <p>A DSL-style API for setting up the disruptor pattern around a ring buffer
 * (aka the Builder pattern).</p>
 *
 * <p>A simple example of setting up the disruptor with two event handlers that
 * must process events in order:</p>
 * <pre>
 * <code>Disruptor&lt;MyEvent&gt; disruptor = new Disruptor&lt;MyEvent&gt;(MyEvent.FACTORY, 32, Executors.newCachedThreadPool());
 * EventHandler&lt;MyEvent&gt; handler1 = new EventHandler&lt;MyEvent&gt;() { ... };
 * EventHandler&lt;MyEvent&gt; handler2 = new EventHandler&lt;MyEvent&gt;() { ... };
 * disruptor.handleEventsWith(handler1);
 * disruptor.after(handler1).handleEventsWith(handler2);
 *
 * RingBuffer ringBuffer = disruptor.start();</code>
 * </pre>
 *
 * @param <T> the type of event used.
 */
public class Disruptor<T>
{
    private final RingBuffer<T> ringBuffer;
    /**
     * 为消费者创建线程用。
     * 查看{@link BasicExecutor}以避免死锁问题。
     */
    private final Executor executor;
    /**
	 * 消费者信息仓库
	 */
    private final ConsumerRepository<T> consumerRepository = new ConsumerRepository<>();
	/**
	 * 运行状态标记
	 */
    private final AtomicBoolean started = new AtomicBoolean(false);
	/**
	 * EventHandler的异常处理器。
	 * 警告！！！默认的异常处理器在EventHandler抛出异常时会终止EventProcessor的线程(退出任务)，可能导致死锁。
	 */
    private ExceptionHandler<? super T> exceptionHandler = new ExceptionHandlerWrapper<>();

    /**
	 * 创建一个Disruptor，默认使用阻塞等待策略。
	 *
	 * 为什么被标记为不推荐呢？ 因为disruptor需要为每一个{@link EventProcessor}创建一个线程，
	 * 采用有界线程池容易导致无法创建足够多的线程而导致死锁。
     * 其实是为每一个{@link EventHandler} {@link WorkHandler}创建一个线程。
	 *
     * Create a new Disruptor. Will default to {@link com.lmax.disruptor.BlockingWaitStrategy} and
     * {@link ProducerType}.MULTI
     *
     * @deprecated Use a {@link ThreadFactory} instead of an {@link Executor} as a the ThreadFactory
     * is able to report errors when it is unable to construct a thread to run a producer.
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer.
     * @param executor       an {@link Executor} to execute event processors.
     */
    @Deprecated
    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final Executor executor)
    {
        this(RingBuffer.createMultiProducer(eventFactory, ringBufferSize), executor);
    }

    /**
	 *
	 * 创建一个Disruptor，可以自定义所有参数。
	 * 不推荐的理由同上。
	 *
     * Create a new Disruptor.
     *
     * @deprecated Use a {@link ThreadFactory} instead of an {@link Executor} as a the ThreadFactory
     * is able to report errors when it is unable to construct a thread to run a producer.
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer, must be power of 2.
     * @param executor       an {@link Executor} to execute event processors.
     * @param producerType   the claim strategy to use for the ring buffer.
     * @param waitStrategy   the wait strategy to use for the ring buffer.
     */
    @Deprecated
    public Disruptor(
        final EventFactory<T> eventFactory,
        final int ringBufferSize,
        final Executor executor,
        final ProducerType producerType,
        final WaitStrategy waitStrategy)
    {
        this(RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy), executor);
    }

    /**
	 * 创建一个Disruptor示例。 默认使用阻塞等待策略和多生产者模型。
	 *
     * Create a new Disruptor. Will default to {@link com.lmax.disruptor.BlockingWaitStrategy} and
     * {@link ProducerType}.MULTI
     *
     * @param eventFactory   the factory to create events in the ring buffer. 事件对象工厂
     * @param ringBufferSize the size of the ring buffer. RingBuffer环形缓冲区大小
     * @param threadFactory  a {@link ThreadFactory} to create threads to for processors. 消费者线程工厂
     */
    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final ThreadFactory threadFactory)
    {
        this(RingBuffer.createMultiProducer(eventFactory, ringBufferSize), new BasicExecutor(threadFactory));
    }

    /**
	 * 创建一个Disruptor 可以自定义所有参数
     * Create a new Disruptor.
     *
     * @param eventFactory   the factory to create events in the ring buffer.
	 *                       事件工厂，为环形缓冲区填充数据使用
     * @param ringBufferSize the size of the ring buffer, must be power of 2.
	 *                       环形缓冲区大小，必须是2的n次方
	 *
     * @param threadFactory  a {@link ThreadFactory} to create threads for processors.
	 *                       事件处理器的线程工厂(每一个EventProcessor需要一个独立的线程)
     * @param producerType   the claim strategy to use for the ring buffer.
	 *                       生产者模型
     * @param waitStrategy   the wait strategy to use for the ring buffer.
	 *                       等待策略，事件处理器在等待可用数据时的策略。
     */
    public Disruptor(
            final EventFactory<T> eventFactory,
            final int ringBufferSize,
            final ThreadFactory threadFactory,
            final ProducerType producerType,
            final WaitStrategy waitStrategy)
    {
        this(
            RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy),
            new BasicExecutor(threadFactory));
    }

    /**
     * Private constructor helper
     */
    private Disruptor(final RingBuffer<T> ringBuffer, final Executor executor)
    {
        this.ringBuffer = ringBuffer;
        this.executor = executor;
    }

    /**
	 * 添加并行消费者，每一个EventHandler都是独立的消费者。
	 * 这些消费者是并行关系，彼此无依赖的。
     * <p>Set up event handlers to handle events from the ring buffer. These handlers will process events
     * as soon as they become available, in parallel.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * @param handlers the event handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers)
    {
        return createEventProcessors(new Sequence[0], handlers);
    }

    /**
	 * 添加并行消费者，每一个EventProcessorFactory创建一个EventProcessor映射为一个消费者。
	 * 这些消费者之间是并行关系
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start these processors when {@link #start()} is called.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>Since this is the start of the chain, the processor factories will always be passed an empty <code>Sequence</code>
     * array, so the factory isn't necessary in this case. This method is provided for consistency with
     * {@link EventHandlerGroup#handleEventsWith(EventProcessorFactory...)} and {@link EventHandlerGroup#then(EventProcessorFactory...)}
     * which do have barrier sequences to provide.</p>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * @param eventProcessorFactories the event processor factories to use to create the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories)
    {
        final Sequence[] barrierSequences = new Sequence[0];
        return createEventProcessors(barrierSequences, eventProcessorFactories);
    }

    /**
	 * 添加并行消费者，每一个EventProcessor映射为一个消费者。
	 * 这些消费者之间是并行关系
	 *
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start this processors when {@link #start()} is called.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the processor <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * @param processors the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    public EventHandlerGroup<T> handleEventsWith(final EventProcessor... processors)
    {
        for (final EventProcessor processor : processors)
        {
            consumerRepository.add(processor);
        }

        final Sequence[] sequences = new Sequence[processors.length];
        for (int i = 0; i < processors.length; i++)
        {
            sequences[i] = processors[i].getSequence();
        }

        ringBuffer.addGatingSequences(sequences);

        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }


    /**
	 * 添加一个多线程的消费者。该消费者会将事件分发到各个WorkHandler。每一个事件只会被其中一个WorkHandler处理。
	 *
     * Set up a {@link WorkerPool} to distribute an event to one of a pool of work handler threads.
     * Each event will only be processed by one of the work handlers.
     * The Disruptor will automatically start this processors when {@link #start()} is called.
     *
     * @param workHandlers the work handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final EventHandlerGroup<T> handleEventsWithWorkerPool(final WorkHandler<T>... workHandlers)
    {
        return createWorkerPool(new Sequence[0], workHandlers);
    }

    /**
	 * 添加事件异常处理器
     * <p>Specify an exception handler to be used for any future event handlers.</p>
     *
     * <p>Note that only event handlers set up after calling this method will use the exception handler.</p>
     *
     * @param exceptionHandler the exception handler to use for any future {@link EventProcessor}.
     * @deprecated This method only applies to future event handlers. Use setDefaultExceptionHandler instead which applies to existing and new event handlers.
     */
    public void handleExceptionsWith(final ExceptionHandler<? super T> exceptionHandler)
    {
        this.exceptionHandler = exceptionHandler;
    }

    /**
	 * 设置默认的异常处理器
     * <p>Specify an exception handler to be used for event handlers and worker pools created by this Disruptor.</p>
     *
     * <p>The exception handler will be used by existing and future event handlers and worker pools created by this Disruptor instance.</p>
     *
     * @param exceptionHandler the exception handler to use.
     */
    @SuppressWarnings("unchecked")
    public void setDefaultExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        checkNotStarted();
        // 如果已修改过异常处理器，不允许在修改
        if (!(this.exceptionHandler instanceof ExceptionHandlerWrapper))
        {
            throw new IllegalStateException("setDefaultExceptionHandler can not be used after handleExceptionsWith");
        }
        ((ExceptionHandlerWrapper<T>)this.exceptionHandler).switchTo(exceptionHandler);
    }

    /**
	 * 为handler添加异常处理设置功能
	 *
     * Override the default exception handler for a specific handler.
     * <pre>disruptorWizard.handleExceptionsIn(eventHandler).with(exceptionHandler);</pre>
     *
     * @param eventHandler the event handler to set a different exception handler for.
     * @return an ExceptionHandlerSetting dsl object - intended to be used by chaining the with method call.
     */
    public ExceptionHandlerSetting<T> handleExceptionsFor(final EventHandler<T> eventHandler)
    {
        return new ExceptionHandlerSetting<>(eventHandler, consumerRepository);
    }

    /**
	 * 创建一个EventHandlerGroup用于添加消费者(EventHandler)之间的依赖。
	 * after之后的eventHandler只能消费已经被依赖的handlers处理过的序号(事件)。
	 * 很重要的方法。
     * <p>Create a group of event handlers to be used as a dependency.
     * For example if the handler <code>A</code> must process events before handler <code>B</code>:</p>
     *
     * <pre><code>dw.after(A).handleEventsWith(B);</code></pre>
     *
     * @param handlers the event handlers, previously set up with {@link #handleEventsWith(com.lmax.disruptor.EventHandler[])},
     *                 that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a dependency barrier over the specified event handlers.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final EventHandlerGroup<T> after(final EventHandler<T>... handlers)
    {
        final Sequence[] sequences = new Sequence[handlers.length];
        for (int i = 0, handlersLength = handlers.length; i < handlersLength; i++)
        {
            sequences[i] = consumerRepository.getSequenceFor(handlers[i]);
        }

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }

    /**
	 * 创建一个EventHandlerGroup用于添加消费者(EventHandler)之间的依赖。
	 * after之后的EventProcessor(WorkHandler)只能消费已经被依赖的EventProcessor处理过的序号(事件)。
	 * 很重要的方法。
	 *
     * Create a group of event processors to be used as a dependency.
     *
     * @param processors the event processors, previously set up with {@link #handleEventsWith(com.lmax.disruptor.EventProcessor...)},
     *                   that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a {@link SequenceBarrier} over the specified event processors.
     * @see #after(com.lmax.disruptor.EventHandler[])
     */
    public EventHandlerGroup<T> after(final EventProcessor... processors)
    {
        for (final EventProcessor processor : processors)
        {
            consumerRepository.add(processor);
        }

        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }

    /**
	 * 这些publish方法是使用数据传输对象发布数据。是对RingBuffer发布事件的一个封装。
	 * 多了一个间接层，可能使用
	 * long sequence=ringBuffer.next();
	 * try{
	 * 		T event= ringBuffer.get(sequence);
	 *     //
	 * } finalLy(){
	 *     ringBuffer.publish(sequence);
	 * }
	 * 更清晰？
	 * 
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     */
    public void publishEvent(final EventTranslator<T> eventTranslator)
    {
        ringBuffer.publishEvent(eventTranslator);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A> Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             A single argument to load into the event
     */
    public <A> void publishEvent(final EventTranslatorOneArg<T, A> eventTranslator, final A arg)
    {
        ringBuffer.publishEvent(eventTranslator, arg);
    }

    /**
     * Publish a batch of events to the ring buffer.
     *
     * @param <A> Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             An array single arguments to load into the events. One Per event.
     */
    public <A> void publishEvents(final EventTranslatorOneArg<T, A> eventTranslator, final A[] arg)
    {
        ringBuffer.publishEvents(eventTranslator, arg);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A> Class of the user supplied argument.
     * @param <B> Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     */
    public <A, B> void publishEvent(final EventTranslatorTwoArg<T, A, B> eventTranslator, final A arg0, final B arg1)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     * @param <A> Class of the user supplied argument.
     * @param <B> Class of the user supplied argument.
     * @param <C> Class of the user supplied argument.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     * @param arg2            The third argument to load into the event
     */
    public <A, B, C> void publishEvent(final EventTranslatorThreeArg<T, A, B, C> eventTranslator, final A arg0, final B arg1, final C arg2)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1, arg2);
    }

    /**
	 * 启动Disruptor，其实就是启动消费者为。
	 * 为每一个EventProcessor创建一个独立的线程。
	 *
	 * 消费者关系的组织必须在启动之前。
	 *
     * <p>Starts the event processors and returns the fully configured ring buffer.</p>
     *
     * <p>The ring buffer is set up to prevent overwriting any entry that is yet to
     * be processed by the slowest event processor.</p>
     *
     * <p>This method must only be called once after all event processors have been added.</p>
     *
     * @return the configured ring buffer.
     */
    public RingBuffer<T> start()
    {
        checkOnlyStartedOnce();
        for (final ConsumerInfo consumerInfo : consumerRepository)
        {
            consumerInfo.start(executor);
        }

        return ringBuffer;
    }

    /**
	 * 请求中断Disruptor执行，也就是终止所有的消费者们。
	 * (应该是可以重新启动的)
	 *
     * Calls {@link com.lmax.disruptor.EventProcessor#halt()} on all of the event processors created via this disruptor.
     */
    public void halt()
    {
        for (final ConsumerInfo consumerInfo : consumerRepository)
        {
            consumerInfo.halt();
        }
    }

    /**
	 * 关闭Disruptor 直到所有的事件处理器停止。(必须保证已经停止向ringBuffer发布数据)
	 *
	 * 本方法不会关闭executor，也不会等待所有的eventProcessor的线程进入终止状态。
	 * 事件处理完，线程不一定退出了，（比如线程可能存在二阶段终止模式）
	 *
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.  It is critical that publishing to the ring buffer has stopped
     * before calling this method, otherwise it may never return.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     */
    public void shutdown()
    {
        try
        {
            shutdown(-1, TimeUnit.MILLISECONDS);
        }
        catch (final TimeoutException e)
        {
            exceptionHandler.handleOnShutdownException(e);
        }
    }

    /**
	 * 关闭disruptor，直到所有事件处理完或超时。
	 * 本方法不会关闭executor，也不会等待所有的eventProcessor的线程进入终止状态。
	 * 事件处理完，线程不一定退出了，（比如线程可能存在二阶段终止模式）
	 *
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     *
     * @param timeout  the amount of time to wait for all events to be processed. <code>-1</code> will give an infinite timeout
     * @param timeUnit the unit the timeOut is specified in
     * @throws TimeoutException if a timeout occurs before shutdown completes.
     */
    public void shutdown(final long timeout, final TimeUnit timeUnit) throws TimeoutException
    {
        final long timeOutAt = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        while (hasBacklog())
        {
            if (timeout >= 0 && System.currentTimeMillis() > timeOutAt)
            {
                throw TimeoutException.INSTANCE;
            }
            // Busy spin
        }
        halt();
    }

    /**
     * The {@link RingBuffer} used by this Disruptor.  This is useful for creating custom
     * event processors if the behaviour of {@link BatchEventProcessor} is not suitable.
     *
     * @return the ring buffer used by this Disruptor.
     */
    public RingBuffer<T> getRingBuffer()
    {
        return ringBuffer;
    }

    /**
	 * 获取生产者的进度(sequence/cursor)
	 *
     * Get the value of the cursor indicating the published sequence.
     *
     * @return value of the cursor for events that have been published.
     */
    public long getCursor()
    {
        return ringBuffer.getCursor();
    }

    /**
	 * 获取缓冲区大小
	 *
     * The capacity of the data structure to hold entries.
     *
     * @return the size of the RingBuffer.
     * @see com.lmax.disruptor.Sequencer#getBufferSize()
     */
    public long getBufferSize()
    {
        return ringBuffer.getBufferSize();
    }

    /**
	 * 从ringBuffer取出指定序列的事件对象
	 *
     * Get the event for a given sequence in the RingBuffer.
     *
     * @param sequence for the event.
     * @return event for the sequence.
     * @see RingBuffer#get(long)
     */
    public T get(final long sequence)
    {
        return ringBuffer.get(sequence);
    }

    /**
     * Get the {@link SequenceBarrier} used by a specific handler. Note that the {@link SequenceBarrier}
     * may be shared by multiple event handlers.
     *
     * @param handler the handler to get the barrier for.
     * @return the SequenceBarrier used by <i>handler</i>.
     */
    public SequenceBarrier getBarrierFor(final EventHandler<T> handler)
    {
        return consumerRepository.getBarrierFor(handler);
    }

    /**
     * Gets the sequence value for the specified event handlers.
     *
     * @param b1 eventHandler to get the sequence for.
     * @return eventHandler's sequence
     */
    public long getSequenceValueFor(final EventHandler<T> b1)
    {
        return consumerRepository.getSequenceFor(b1).get();
    }

    /**
	 * 确定是否还有消费者未消费完所有的事件
     * Confirms if all messages have been consumed by all event processors
     */
    private boolean hasBacklog()
    {
        final long cursor = ringBuffer.getCursor();
        for (final Sequence consumer : consumerRepository.getLastSequenceInChain(false))
        {
            if (cursor > consumer.get())
            {
                return true;
            }
        }
        return false;
    }

	/**
	 * 创建事件处理器(创建BatchEventProcessor消费者)
	 * @param barrierSequences 屏障sequences， {@link com.lmax.disruptor.ProcessingSequenceBarrier#dependentSequence}
	 *                            消费者的消费进度需要慢于它的前驱消费者
	 * @param eventHandlers 事件处理方法 每一个EventHandler都会被包装为{@link BatchEventProcessor}(一个独立的消费者).
	 * @return
	 */
    EventHandlerGroup<T> createEventProcessors(
        final Sequence[] barrierSequences,
        final EventHandler<? super T>[] eventHandlers)
    {
    	// 组织消费者之间的关系只能在启动之前
        checkNotStarted();

        // 收集添加的消费者的序号
        final Sequence[] processorSequences = new Sequence[eventHandlers.length];
        // 本批次消费由于添加在同一个节点之后，因此共享该屏障
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        // 创建单线程消费者(BatchEventProcessor)
        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++)
        {
            final EventHandler<? super T> eventHandler = eventHandlers[i];

            final BatchEventProcessor<T> batchEventProcessor =
                new BatchEventProcessor<>(ringBuffer, barrier, eventHandler);

            if (exceptionHandler != null)
            {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }

            // 添加到消费者信息仓库中
            consumerRepository.add(batchEventProcessor, eventHandler, barrier);
            processorSequences[i] = batchEventProcessor.getSequence();
        }

        // 更新网关序列(生产者只需要关注所有的末端消费者节点的序列)
        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

	/**
	 * 更新网关序列(当消费者链后端添加新节点时)
	 * @param barrierSequences 新节点依赖的网关序列(屏障序列)，这些序列有了后继节点，就不再是网关序列了
	 * @param processorSequences 新增加的节点的序列，新增的在消费者链的末端，因此它们的序列就是新增的网关序列
	 */
    private void updateGatingSequencesForNextInChain(final Sequence[] barrierSequences, final Sequence[] processorSequences)
    {
        if (processorSequences.length > 0)
        {
        	// 将新增加的消费者节点序列添加到网关序列中
            ringBuffer.addGatingSequences(processorSequences);

            // 移除新节点的网关序列，这些序列有了后继节点，就不再是网关序列了
            for (final Sequence barrierSequence : barrierSequences)
            {
                ringBuffer.removeGatingSequence(barrierSequence);
            }

            // 将这些序列标记为不再是消费者链的最后节点
            consumerRepository.unMarkEventProcessorsAsEndOfChain(barrierSequences);
        }
    }

	/**
	 * 注释详见{@link #createEventProcessors(Sequence[], EventHandler[])}
	 */
    EventHandlerGroup<T> createEventProcessors(
        final Sequence[] barrierSequences, final EventProcessorFactory<T>[] processorFactories)
    {
        final EventProcessor[] eventProcessors = new EventProcessor[processorFactories.length];
        for (int i = 0; i < processorFactories.length; i++)
        {
            eventProcessors[i] = processorFactories[i].createEventProcessor(ringBuffer, barrierSequences);
        }

        return handleEventsWith(eventProcessors);
    }


	/**
	 * 创建一个多线程的消费者，消费者的线程数取决于workHandler的数量
	 *
	 * @param barrierSequences WorkPool消费者的所有前驱节点的序列，作为新消费者的依赖序列
	 * @param workHandlers 线程池中处理事件的单元，每一个都会包装为{@link com.lmax.disruptor.WorkProcessor}，
	 *                        数组长度决定线程的数量。
	 *                        如果这些语法觉得有点奇怪的是正常的，为了更好的灵活性，比传数量会好一些
	 */
    EventHandlerGroup<T> createWorkerPool(
        final Sequence[] barrierSequences, final WorkHandler<? super T>[] workHandlers)
    {
        final SequenceBarrier sequenceBarrier = ringBuffer.newBarrier(barrierSequences);
        final WorkerPool<T> workerPool = new WorkerPool<>(ringBuffer, sequenceBarrier, exceptionHandler, workHandlers);


        consumerRepository.add(workerPool, sequenceBarrier);

        final Sequence[] workerSequences = workerPool.getWorkerSequences();

        updateGatingSequencesForNextInChain(barrierSequences, workerSequences);

        return new EventHandlerGroup<>(this, consumerRepository, workerSequences);
    }

	/**
	 * 确保disruptor还未启动
	 */
    private void checkNotStarted()
    {
        if (started.get())
        {
            throw new IllegalStateException("All event handlers must be added before calling starts.");
        }
    }

	/**
	 * 确保disruptor只启动一次
	 */
    private void checkOnlyStartedOnce()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("Disruptor.start() must only be called once.");
        }
    }

    @Override
    public String toString()
    {
        return "Disruptor{" +
            "ringBuffer=" + ringBuffer +
            ", started=" + started +
            ", executor=" + executor +
            '}';
    }
}
