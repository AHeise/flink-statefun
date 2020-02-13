/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.statefun.flink.core.httpfn;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okio.Timeout;
import org.apache.flink.statefun.flink.core.backpressure.BoundedExponentialBackoff;
import org.apache.flink.util.function.RunnableWithException;

@SuppressWarnings("NullableProblems")
final class RetryingCallback implements Callback {
  private static final Duration INITIAL_BACKOFF_DURATION = Duration.ofMillis(10);

  private static final Set<Integer> RETRYABLE_HTTP_CODES =
      new HashSet<>(Arrays.asList(409, 420, 408, 429, 499, 500));

  private final CompletableFuture<Response> resultFuture;
  private final BoundedExponentialBackoff backoff;

  RetryingCallback(Timeout timeout) {
    this.resultFuture = new CompletableFuture<>();
    this.backoff = new BoundedExponentialBackoff(INITIAL_BACKOFF_DURATION, duration(timeout));
  }

  CompletableFuture<Response> future() {
    return resultFuture;
  }

  @Override
  public void onFailure(Call call, IOException cause) {
    tryWithFuture(() -> onFailureUnsafe(call, cause));
  }

  @Override
  public void onResponse(Call call, Response response) {
    tryWithFuture(() -> onResponseUnsafe(call, response));
  }

  private void onFailureUnsafe(Call call, IOException cause) {
    if (!retryAfterApplyingBackoff(call)) {
      throw new IllegalStateException(
          "Maximal request time has elapsed. Last cause is attached", cause);
    }
  }

  private void onResponseUnsafe(Call call, Response response) {
    if (response.isSuccessful()) {
      resultFuture.complete(response);
      return;
    }
    if (!RETRYABLE_HTTP_CODES.contains(response.code()) && response.code() < 500) {
      throw new IllegalStateException("Non successful HTTP response code " + response.code());
    }
    if (!retryAfterApplyingBackoff(call)) {
      throw new IllegalStateException(
          "Maximal request time has elapsed. Last known error is: invalid HTTP response code "
              + response.code());
    }
  }

  /**
   * Retires the original call, after applying backoff.
   *
   * @return if the request was retried successfully, false otherwise.
   */
  private boolean retryAfterApplyingBackoff(Call call) {
    if (backoff.applyNow()) {
      call.clone().enqueue(this);
      return true;
    }
    return false;
  }

  /**
   * Executes the runnable, and completes {@link #resultFuture} with any exceptions thrown, during
   * its execution.
   */
  private void tryWithFuture(RunnableWithException runnable) {
    try {
      runnable.run();
    } catch (Throwable t) {
      resultFuture.completeExceptionally(t);
    }
  }

  private static Duration duration(Timeout timeout) {
    return Duration.ofNanos(timeout.timeoutNanos());
  }
}
