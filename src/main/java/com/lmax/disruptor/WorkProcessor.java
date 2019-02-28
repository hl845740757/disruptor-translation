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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorkPool消费者里面的事件处理单元，不是消费者，只是一个消费者里面的一个元件。
 * 多个WorkProcessor协作构成WorkPool消费者
 *
 * <p>A {@link WorkProcessor} wraps a single {@link WorkHandler}, effectively consuming the sequence
 * and ensuring appropriate barriers.</p>
 *
 * <p>Generally, this will be used as part of a {@link WorkerPool}.</p>
 *
 * @param <T> event implementation storing the details for the work to processed.
 */
public final class WorkProcessor<T> implements EventProcessor {
	/**
	 * 运行状态
	 */
    private final AtomicBoolean running = new AtomicBoolean(false);
	/**
	 * workProcessor处理进度(上一次处理的序号)
	 * 为何要是Sequence这个线程安全的对象呢？因为会被生产者线程们查询
	 */
	private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
	/**
	 * 生产者消费者共享的数据结构
	 */
    private final RingBuffer<T> ringBuffer;
	/**
	 * WorkProcessor所属的消费者的屏障
	 */
	private final SequenceBarrier sequenceBarrier;
	/**
	 * WorkProcessor绑定的事件处理器
	 */
    private final WorkHandler<? super T> workHandler;
	/**
	 * workProcessor绑定的异常处理器
	 */
	private final ExceptionHandler<? super T> exceptionHandler;
	/**
	 * WorkProcessor所属的消费者的进度信息
	 * 每消费一个事件之后需要同步到所属的WorkerPool中的(更新其所属的消费者的进度)
	 * {@link WorkerPool#workSequence}
	 */
    private final Sequence workSequence;

    private final EventReleaser eventReleaser = new EventReleaser()
    {
        @Override
        public void release()
        {
            sequence.set(Long.MAX_VALUE);
        }
    };

    private final TimeoutHandler timeoutHandler;

    /**
     * Construct a {@link WorkProcessor}.
     *
     * @param ringBuffer       to which events are published.
     * @param sequenceBarrier  on which it is waiting.
     * @param workHandler      is the delegate to which events are dispatched.
     * @param exceptionHandler to be called back when an error occurs
     * @param workSequence     from which to claim the next event to be worked on.  It should always be initialised
     *                         as {@link Sequencer#INITIAL_CURSOR_VALUE}
     */
    public WorkProcessor(
        final RingBuffer<T> ringBuffer,
        final SequenceBarrier sequenceBarrier,
        final WorkHandler<? super T> workHandler,
        final ExceptionHandler<? super T> exceptionHandler,
        final Sequence workSequence)
    {
        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.workHandler = workHandler;
        this.exceptionHandler = exceptionHandler;
        this.workSequence = workSequence;

        if (this.workHandler instanceof EventReleaseAware)
        {
            ((EventReleaseAware) this.workHandler).setEventReleaser(eventReleaser);
        }

        timeoutHandler = (workHandler instanceof TimeoutHandler) ? (TimeoutHandler) workHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

	/**
	 *
	 */
	@Override
    public void halt()
    {
        running.set(false);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    /**
     * It is ok to have another thread re-run this method after a halt().
     *
     * @throws IllegalStateException if this processor is already running
     */
    @Override
    public void run()
    {
        if (!running.compareAndSet(false, true))
        {
            throw new IllegalStateException("Thread is already running");
        }
        // 清除特定状态(可理解为清除线程的中断状态)
        sequenceBarrier.clearAlert();

        notifyStart();

        // 是否处理了一个事件，当处理了一个事件只会需要再次竞争编号，进行下次消费
        boolean processedSequence = true;
        // 看见的已发布序号的缓存，注意！这里是局部变量，在该变量上无竞争
        long cachedAvailableSequence = Long.MIN_VALUE;
        // 下一个要消费的序号(要消费的事件编号)，注意起始为-1 ，注意与BatchEventProcessor的区别
		// BatchEventProcessor初始值为 sequence.get()+1
        long nextSequence = sequence.get();
        // 要消费的事件对象
        T event = null;
        while (true)
        {
            try
            {
				// 首先和workSequence同步进度，然后尝试消费下一个序号，也就是workProcessor的进度最终不会低于整体的进度。
				// 当WorkHandler抛出异常时，这可以防止序列增加的太大

                // if previous sequence was processed - fetch the next sequence and set
                // that we have successfully processed the previous sequence
                // typically, this will be true
                // this prevents the sequence getting too far forward if an exception
                // is thrown from the WorkHandler
                if (processedSequence)
                {
                    processedSequence = false;
					// 竞争下一个消费序号
                    do
                    {
                    	// 存为local variable 减少大量的volatile变量读，且保证操作过程中的一致性
						// 不存为本地变量会发生错误，每次get可能会取到的不一样，从而导致错误
                        nextSequence = workSequence.get() + 1L;
                        sequence.set(nextSequence - 1L);
                    }
                    while (!workSequence.compareAndSet(nextSequence - 1L, nextSequence));
                    // while 还没处理过事件，那么第一次更新是干嘛？
					// 是将进度从-1更新到0表示开始，-1是不需要消费的
                }

                // 每次只处理一个事件，和 BatchEventProcessor有区别,因为WorkPool代表的消费者中可能有多个事件处理器，他们会竞争序号。
				// 每次竞争一个序号，因此每次只消费一个
                if (cachedAvailableSequence >= nextSequence)
                {
                    event = ringBuffer.get(nextSequence);
                    workHandler.onEvent(event);
                    processedSequence = true;
                }
                else
                {
                    cachedAvailableSequence = sequenceBarrier.waitFor(nextSequence);
                }
            }
            catch (final TimeoutException e)
            {
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                if (!running.get())
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                // handle, mark as processed, unless the exception handler threw an exception
                exceptionHandler.handleEventException(ex, nextSequence, event);
                processedSequence = true;
            }
        }

        notifyShutdown();

        running.set(false);
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

    private void notifyStart()
    {
        if (workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) workHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    private void notifyShutdown()
    {
        if (workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) workHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}
