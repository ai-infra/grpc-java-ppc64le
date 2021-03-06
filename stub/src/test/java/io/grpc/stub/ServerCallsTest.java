/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.stub;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Tests for {@link ServerCalls}.
 */
@RunWith(JUnit4.class)
public class ServerCallsTest {
  static final MethodDescriptor<Integer, Integer> STREAMING_METHOD =
      MethodDescriptor.<Integer, Integer>newBuilder()
          .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
          .setFullMethodName("some/method")
          .setRequestMarshaller(new IntegerMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();

  static final MethodDescriptor<Integer, Integer> UNARY_METHOD = STREAMING_METHOD.toBuilder()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName("some/unarymethod")
      .build();

  private final ServerCallRecorder serverCall = new ServerCallRecorder();

  @Test
  public void runtimeStreamObserverIsServerCallStreamObserver() throws Exception {
    final AtomicBoolean invokeCalled = new AtomicBoolean();
    final AtomicBoolean onCancelCalled = new AtomicBoolean();
    final AtomicBoolean onReadyCalled = new AtomicBoolean();
    final AtomicReference<ServerCallStreamObserver<Integer>> callObserver =
        new AtomicReference<ServerCallStreamObserver<Integer>>();
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                assertTrue(responseObserver instanceof ServerCallStreamObserver);
                ServerCallStreamObserver<Integer> serverCallObserver =
                    (ServerCallStreamObserver<Integer>) responseObserver;
                callObserver.set(serverCallObserver);
                serverCallObserver.setOnCancelHandler(new Runnable() {
                  @Override
                  public void run() {
                    onCancelCalled.set(true);
                  }
                });
                serverCallObserver.setOnReadyHandler(new Runnable() {
                  @Override
                  public void run() {
                    onReadyCalled.set(true);
                  }
                });
                invokeCalled.set(true);
                return new ServerCalls.NoopStreamObserver<Integer>();
              }
            });
    ServerCall.Listener<Integer> callListener =
        callHandler.startCall(serverCall, new Metadata());
    serverCall.isReady = true;
    serverCall.isCancelled = false;
    assertTrue(callObserver.get().isReady());
    assertFalse(callObserver.get().isCancelled());
    callListener.onReady();
    callListener.onMessage(1);
    callListener.onCancel();
    assertTrue(invokeCalled.get());
    assertTrue(onReadyCalled.get());
    assertTrue(onCancelCalled.get());
    serverCall.isReady = false;
    serverCall.isCancelled = true;
    assertFalse(callObserver.get().isReady());
    assertTrue(callObserver.get().isCancelled());
    // Is called twice, once to permit the first message and once again after the first message
    // has been processed (auto flow control)
    assertThat(serverCall.requestCalls).containsExactly(1, 1).inOrder();
  }

  @Test
  public void cannotSetOnCancelHandlerAfterServiceInvocation() throws Exception {
    final AtomicReference<ServerCallStreamObserver<Integer>> callObserver =
        new AtomicReference<ServerCallStreamObserver<Integer>>();
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                callObserver.set((ServerCallStreamObserver<Integer>) responseObserver);
                return new ServerCalls.NoopStreamObserver<Integer>();
              }
            });
    ServerCall.Listener<Integer> callListener =
        callHandler.startCall(serverCall, new Metadata());
    callListener.onMessage(1);
    try {
      callObserver.get().setOnCancelHandler(new Runnable() {
        @Override
        public void run() {
        }
      });
      fail("Cannot set onCancel handler after service invocation");
    } catch (IllegalStateException expected) {
      // Expected
    }
  }

  @Test
  public void cannotSetOnReadyHandlerAfterServiceInvocation() throws Exception {
    final AtomicReference<ServerCallStreamObserver<Integer>> callObserver =
        new AtomicReference<ServerCallStreamObserver<Integer>>();
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                callObserver.set((ServerCallStreamObserver<Integer>) responseObserver);
                return new ServerCalls.NoopStreamObserver<Integer>();
              }
            });
    ServerCall.Listener<Integer> callListener =
        callHandler.startCall(serverCall, new Metadata());
    callListener.onMessage(1);
    try {
      callObserver.get().setOnReadyHandler(new Runnable() {
        @Override
        public void run() {
        }
      });
      fail("Cannot set onReady after service invocation");
    } catch (IllegalStateException expected) {
      // Expected
    }
  }

  @Test
  public void cannotDisableAutoFlowControlAfterServiceInvocation() throws Exception {
    final AtomicReference<ServerCallStreamObserver<Integer>> callObserver =
        new AtomicReference<ServerCallStreamObserver<Integer>>();
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                callObserver.set((ServerCallStreamObserver<Integer>) responseObserver);
                return new ServerCalls.NoopStreamObserver<Integer>();
              }
            });
    ServerCall.Listener<Integer> callListener =
        callHandler.startCall(serverCall, new Metadata());
    callListener.onMessage(1);
    try {
      callObserver.get().disableAutoInboundFlowControl();
      fail("Cannot set onCancel handler after service invocation");
    } catch (IllegalStateException expected) {
      // Expected
    }
  }

  @Test
  public void disablingInboundAutoFlowControlSuppressesRequestsForMoreMessages() throws Exception {
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                ServerCallStreamObserver<Integer> serverCallObserver =
                    (ServerCallStreamObserver<Integer>) responseObserver;
                serverCallObserver.disableAutoInboundFlowControl();
                return new ServerCalls.NoopStreamObserver<Integer>();
              }
            });
    ServerCall.Listener<Integer> callListener =
        callHandler.startCall(serverCall, new Metadata());
    callListener.onReady();
    // Transport should not call this if nothing has been requested but forcing it here
    // to verify that message delivery does not trigger a call to request(1).
    callListener.onMessage(1);
    // Should never be called
    assertThat(serverCall.requestCalls).isEmpty();
  }

  @Test
  public void disablingInboundAutoFlowControlForUnaryHasNoEffect() throws Exception {
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncUnaryCall(
            new ServerCalls.UnaryMethod<Integer, Integer>() {
              @Override
              public void invoke(Integer req, StreamObserver<Integer> responseObserver) {
                ServerCallStreamObserver<Integer> serverCallObserver =
                    (ServerCallStreamObserver<Integer>) responseObserver;
                serverCallObserver.disableAutoInboundFlowControl();
              }
            });
    callHandler.startCall(serverCall, new Metadata());
    // Auto inbound flow-control always requests 2 messages for unary to detect a violation
    // of the unary semantic.
    assertThat(serverCall.requestCalls).containsExactly(2);
  }

  @Test
  public void onReadyHandlerCalledForUnaryRequest() throws Exception {
    final AtomicInteger onReadyCalled = new AtomicInteger();
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncServerStreamingCall(
            new ServerCalls.ServerStreamingMethod<Integer, Integer>() {
              @Override
              public void invoke(Integer req, StreamObserver<Integer> responseObserver) {
                ServerCallStreamObserver<Integer> serverCallObserver =
                    (ServerCallStreamObserver<Integer>) responseObserver;
                serverCallObserver.setOnReadyHandler(new Runnable() {
                  @Override
                  public void run() {
                    onReadyCalled.incrementAndGet();
                  }
                });
              }
            });
    ServerCall.Listener<Integer> callListener =
        callHandler.startCall(serverCall, new Metadata());
    serverCall.isReady = true;
    serverCall.isCancelled = false;
    callListener.onReady();
    // On ready is not called until the unary request message is delivered
    assertEquals(0, onReadyCalled.get());
    // delivering the message doesn't trigger onReady listener either
    callListener.onMessage(1);
    assertEquals(0, onReadyCalled.get());
    // half-closing triggers the unary request delivery and onReady
    callListener.onHalfClose();
    assertEquals(1, onReadyCalled.get());
    // Next on ready event from the transport triggers listener
    callListener.onReady();
    assertEquals(2, onReadyCalled.get());
  }

  @Test
  public void inprocessTransportManualFlow() throws Exception {
    final Semaphore semaphore = new Semaphore(1);
    ServerServiceDefinition service = ServerServiceDefinition.builder(
        new ServiceDescriptor("some", STREAMING_METHOD))
        .addMethod(STREAMING_METHOD, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              int iteration;

              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                final ServerCallStreamObserver<Integer> serverCallObserver =
                    (ServerCallStreamObserver<Integer>) responseObserver;
                serverCallObserver.setOnReadyHandler(new Runnable() {
                  @Override
                  public void run() {
                    while (serverCallObserver.isReady()) {
                      serverCallObserver.onNext(iteration);
                    }
                    iteration++;
                    semaphore.release();
                  }
                });
                return new ServerCalls.NoopStreamObserver<Integer>() {
                  @Override
                  public void onCompleted() {
                    serverCallObserver.onCompleted();
                  }
                };
              }
            }))
        .build();
    long tag = System.nanoTime();
    InProcessServerBuilder.forName("go-with-the-flow" + tag).addService(service).build().start();
    ManagedChannel channel = InProcessChannelBuilder.forName("go-with-the-flow" + tag).build();
    final ClientCall<Integer, Integer> clientCall = channel.newCall(STREAMING_METHOD,
        CallOptions.DEFAULT);
    final CountDownLatch latch = new CountDownLatch(1);
    final int[] receivedMessages = new int[6];
    clientCall.start(new ClientCall.Listener<Integer>() {
      int index;

      @Override
      public void onMessage(Integer message) {
        receivedMessages[index++] = message;
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        latch.countDown();
      }
    }, new Metadata());
    semaphore.acquire();
    clientCall.request(1);
    semaphore.acquire();
    clientCall.request(2);
    semaphore.acquire();
    clientCall.request(3);
    clientCall.halfClose();
    latch.await(5, TimeUnit.SECONDS);
    // Very that number of messages produced in each onReady handler call matches the number
    // requested by the client.
    assertArrayEquals(new int[]{0, 1, 1, 2, 2, 2}, receivedMessages);
  }

  public static class IntegerMarshaller implements MethodDescriptor.Marshaller<Integer> {
    @Override
    public InputStream stream(Integer value) {
      try {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4);
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(value);
        return new ByteArrayInputStream(baos.toByteArray());
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    @Override
    public Integer parse(InputStream stream) {
      try {
        DataInputStream dis = new DataInputStream(stream);
        return dis.readInt();
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
  }

  private static class ServerCallRecorder extends ServerCall<Integer, Integer> {
    private List<Integer> requestCalls = new ArrayList<Integer>();
    private Metadata headers;
    private Integer message;
    private Metadata trailers;
    private Status status;
    private boolean isCancelled;
    private MethodDescriptor<Integer, Integer> methodDescriptor;
    private boolean isReady;

    @Override
    public void request(int numMessages) {
      requestCalls.add(numMessages);
    }

    @Override
    public void sendHeaders(Metadata headers) {
      this.headers = headers;
    }

    @Override
    public void sendMessage(Integer message) {
      this.message = message;
    }

    @Override
    public void close(Status status, Metadata trailers) {
      this.status = status;
      this.trailers = trailers;
    }

    @Override
    public boolean isCancelled() {
      return isCancelled;
    }

    @Override
    public boolean isReady() {
      return isReady;
    }

    @Override
    public MethodDescriptor<Integer, Integer> getMethodDescriptor() {
      return methodDescriptor;
    }
  }
}
