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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * 批量事件处理器，一个单线程的消费者(只有一个EventProcessor)
 * 代理EventHandler，管理处理事件以外的其他事情(如：拉取事件，等待事件...)。
 * <p>
 * 如果{@link EventHandler}实现了{@link LifecycleAware}，那么在线程启动后停止前将会收到一个通知。
 *
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    /**
     * 闲置状态
     */
    private static final int IDLE = 0;
    /**
     * 暂停状态
     */
    private static final int HALTED = IDLE + 1;
    /**
	 * 运行状态
	 */
    private static final int RUNNING = HALTED + 1;

    /**
     * 运行状态标记
     */
    private final AtomicInteger running = new AtomicInteger(IDLE);
    /**
     * 处理事件时的异常处理器
     * 警告！！！
     * 默认的异常处理器{@link com.lmax.disruptor.dsl.Disruptor#exceptionHandler}，在出现异常时会打断运行，会导致死锁！
     */
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();
    /**
     * 数据提供者(RingBuffer)
     */
    private final DataProvider<T> dataProvider;
    /**
	 * 消费者依赖的屏障，用于协调该消费者与生产者/其他消费者之间的速度。
	 */
    private final SequenceBarrier sequenceBarrier;
    /**
     * 事件处理方法，真正处理事件的对象
     */
    private final EventHandler<? super T> eventHandler;
    /**
     * 消费者的消费进度
     */
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    private final TimeoutHandler timeoutHandler;
    /**
     * 批处理开始时的通知器
     */
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

		// 如果eventHandler还实现了其他接口
        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
            (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
            (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        // 标记已被中断，如果事件处理器在屏障上等待，那么需要“唤醒”事件处理器，响应中断/停止请求。
        running.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
	 * 暂停以后交给下一个线程继续执行是线程安全的
	 * It is ok to have another thread rerun this method after a halt().
     * 
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
    	// 原子变量，当能从IDLE切换到RUNNING状态时，前一个线程一定退出了run()
		// 具备happens-before原则，上一个线程修改的状态对于新线程是可见的。
        if (running.compareAndSet(IDLE, RUNNING))
        {
            // 检查中断、停止请求
            sequenceBarrier.clearAlert();

            // 通知已启动
            // 警告：如果抛出异常将导致线程终止，小心死锁风险。此外：notifyShutdown 也不会被调用。
            notifyStart();
            try
            {
                if (running.get() == RUNNING)
                {
                    processEvents();
                }
            }
            finally
            {
                // notifyStart调用成功才会走到这里
                notifyShutdown();
                // 在退出的时候会恢复到IDLE状态，且是原子变量，具备happens-before原则
				// 由volatile支持
                running.set(IDLE);
            }
        }
        else
        {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING)
            {
                throw new IllegalStateException("Thread is already running");
            }
            else
            {
                // 到这里可能是running状态，主要是lmax支持的多了，如果不允许重用，线程退出时(notifyShutdown后)不修改为idle状态，那么便不存在该问题。
                earlyExit();
            }
        }
    }

	/**
	 * 处理事件，核心逻辑
	 */
    private void processEvents()
    {
        T event = null;
        // 下一个消费的序号， -1 到 0，这个很重要，对于理解 WorkProcessor有帮助
		// -1是不需要消费的，第一个要消费的是0
        long nextSequence = sequence.get() + 1L;

        // 死循环，因此不会让出线程，需要独立的线程(每一个EventProcessor都需要独立的线程)
        while (true)
        {
            try
            {
            	// 通过屏障获取到的最大可用序号，比起自己去查询的话，类自身就简单干净一些，复用性更好
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                if (batchStartAware != null)
                {
                	// 批量处理事件开始时发送通知
                    batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                }

                // 批量消费，由于没有其它事件处理器和我竞争序号，这些序号我都是可以消费的
                while (nextSequence <= availableSequence)
                {
                    event = dataProvider.get(nextSequence);
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    nextSequence++;
                }

                // 更新消费进度(批量消费，每次消费只更新一次Sequence，减少性能消耗	)
                // 这里有毒：availableSequence理论上可能小于nextSequence，也就是可能是无效的，因此应该只在成功消费了事件之后更新
                sequence.set(availableSequence);
            }
            catch (final TimeoutException e)
            {
                // 等待sequence超时，进行重试
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                // 检查到中断/停止请求，如果发现已经不是运行状态，则退出while死循环
                if (running.get() != RUNNING)
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
            	// 警告：如果在处理异常时抛出新的异常，会导致跳出while循环，导致BatchEventProcessor停止工作，可能导致死锁
				// 而系统默认的异常处理会将其包装为RuntimeException！！！
                exceptionHandler.handleEventException(ex, nextSequence, event);

				// 成功处理异常后标记当前事件已被处理
				// 警告：如果自己实现的等待策略，抛出了TimeoutException、AlertException以外的异常，从而走到这里，将导致该sequence被跳过！
                // 从而导致数据/信号丢失！严重bug！
                // 严格的说，lmax这里的实现对于扩展并不是特别的安全， 安全一点的话，使用两个try块更加安全，
                // 一个try块负责获取availableSequence，第二个try块负责事件处理
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * 通知{@link EventHandler}事件处理器启动了
     *
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                // 警告：如果这里抛出了新异常，将导致线程终止！小心死锁风险。
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}