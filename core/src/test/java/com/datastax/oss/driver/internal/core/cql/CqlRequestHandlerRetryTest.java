/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.driver.internal.core.cql;

import static com.datastax.oss.driver.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.datastax.oss.driver.TestDataProviders;
import com.datastax.oss.driver.api.core.CoreConsistencyLevel;
import com.datastax.oss.driver.api.core.connection.HeartbeatException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.retry.RetryDecision;
import com.datastax.oss.driver.api.core.retry.RetryPolicy;
import com.datastax.oss.driver.api.core.servererrors.BootstrappingException;
import com.datastax.oss.driver.api.core.servererrors.CoreWriteType;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.ServerError;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.response.Error;
import com.datastax.oss.protocol.internal.response.error.ReadTimeout;
import com.datastax.oss.protocol.internal.response.error.Unavailable;
import com.datastax.oss.protocol.internal.response.error.WriteTimeout;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import java.util.Iterator;
import java.util.concurrent.CompletionStage;
import org.junit.Test;
import org.mockito.Mockito;

public class CqlRequestHandlerRetryTest extends CqlRequestHandlerTestBase {

  @Test
  @UseDataProvider("allIdempotenceConfigs")
  public void should_always_try_next_node_if_bootstrapping(
      boolean defaultIdempotence, SimpleStatement statement) {
    try (RequestHandlerTestHarness harness =
        RequestHandlerTestHarness.builder()
            .withDefaultIdempotence(defaultIdempotence)
            .withResponse(
                node1,
                defaultFrameOf(
                    new Error(ProtocolConstants.ErrorCode.IS_BOOTSTRAPPING, "mock message")))
            .withResponse(node2, defaultFrameOf(singleRow()))
            .build()) {

      CompletionStage<AsyncResultSet> resultSetFuture =
          new CqlRequestAsyncHandler(statement, harness.getSession(), harness.getContext(), "test")
              .handle();

      assertThat(resultSetFuture)
          .isSuccess(
              resultSet -> {
                Iterator<Row> rows = resultSet.currentPage().iterator();
                assertThat(rows.hasNext()).isTrue();
                assertThat(rows.next().getString("message")).isEqualTo("hello, world");

                ExecutionInfo executionInfo = resultSet.getExecutionInfo();
                assertThat(executionInfo.getCoordinator()).isEqualTo(node2);
                assertThat(executionInfo.getErrors()).hasSize(1);
                assertThat(executionInfo.getErrors().get(0).getKey()).isEqualTo(node1);
                assertThat(executionInfo.getErrors().get(0).getValue())
                    .isInstanceOf(BootstrappingException.class);
                assertThat(executionInfo.getIncomingPayload()).isEmpty();
                assertThat(executionInfo.getPagingState()).isNull();
                assertThat(executionInfo.getSpeculativeExecutionCount()).isEqualTo(0);
                assertThat(executionInfo.getSuccessfulExecutionIndex()).isEqualTo(0);
                assertThat(executionInfo.getWarnings()).isEmpty();

                Mockito.verifyNoMoreInteractions(harness.getContext().retryPolicy());
              });
    }
  }

  @Test
  @UseDataProvider("allIdempotenceConfigs")
  public void should_always_rethrow_query_validation_error(
      boolean defaultIdempotence, SimpleStatement statement) {
    try (RequestHandlerTestHarness harness =
        RequestHandlerTestHarness.builder()
            .withDefaultIdempotence(defaultIdempotence)
            .withResponse(
                node1,
                defaultFrameOf(new Error(ProtocolConstants.ErrorCode.INVALID, "mock message")))
            .build()) {

      CompletionStage<AsyncResultSet> resultSetFuture =
          new CqlRequestAsyncHandler(statement, harness.getSession(), harness.getContext(), "test")
              .handle();

      assertThat(resultSetFuture)
          .isFailed(
              error -> {
                assertThat(error)
                    .isInstanceOf(InvalidQueryException.class)
                    .hasMessage("mock message");
                Mockito.verifyNoMoreInteractions(harness.getContext().retryPolicy());
              });
    }
  }

  @Test
  @UseDataProvider("failureAndIdempotent")
  public void should_try_next_node_if_idempotent_and_retry_policy_decides_so(
      FailureScenario failureScenario, boolean defaultIdempotence, SimpleStatement statement) {
    RequestHandlerTestHarness.Builder harnessBuilder =
        RequestHandlerTestHarness.builder().withDefaultIdempotence(defaultIdempotence);
    failureScenario.mockRequestError(harnessBuilder, node1);
    harnessBuilder.withResponse(node2, defaultFrameOf(singleRow()));

    try (RequestHandlerTestHarness harness = harnessBuilder.build()) {
      failureScenario.mockRetryPolicyDecision(
          harness.getContext().retryPolicy(), RetryDecision.RETRY_NEXT);

      CompletionStage<AsyncResultSet> resultSetFuture =
          new CqlRequestAsyncHandler(statement, harness.getSession(), harness.getContext(), "test")
              .handle();

      assertThat(resultSetFuture)
          .isSuccess(
              resultSet -> {
                Iterator<Row> rows = resultSet.currentPage().iterator();
                assertThat(rows.hasNext()).isTrue();
                assertThat(rows.next().getString("message")).isEqualTo("hello, world");

                ExecutionInfo executionInfo = resultSet.getExecutionInfo();
                assertThat(executionInfo.getCoordinator()).isEqualTo(node2);
                assertThat(executionInfo.getErrors()).hasSize(1);
                assertThat(executionInfo.getErrors().get(0).getKey()).isEqualTo(node1);
              });
    }
  }

  @Test
  @UseDataProvider("failureAndIdempotent")
  public void should_try_same_node_if_idempotent_and_retry_policy_decides_so(
      FailureScenario failureScenario, boolean defaultIdempotence, SimpleStatement statement) {
    RequestHandlerTestHarness.Builder harnessBuilder =
        RequestHandlerTestHarness.builder().withDefaultIdempotence(defaultIdempotence);
    failureScenario.mockRequestError(harnessBuilder, node1);
    harnessBuilder.withResponse(node1, defaultFrameOf(singleRow()));

    try (RequestHandlerTestHarness harness = harnessBuilder.build()) {
      failureScenario.mockRetryPolicyDecision(
          harness.getContext().retryPolicy(), RetryDecision.RETRY_SAME);

      CompletionStage<AsyncResultSet> resultSetFuture =
          new CqlRequestAsyncHandler(statement, harness.getSession(), harness.getContext(), "test")
              .handle();

      assertThat(resultSetFuture)
          .isSuccess(
              resultSet -> {
                Iterator<Row> rows = resultSet.currentPage().iterator();
                assertThat(rows.hasNext()).isTrue();
                assertThat(rows.next().getString("message")).isEqualTo("hello, world");

                ExecutionInfo executionInfo = resultSet.getExecutionInfo();
                assertThat(executionInfo.getCoordinator()).isEqualTo(node1);
                assertThat(executionInfo.getErrors()).hasSize(1);
                assertThat(executionInfo.getErrors().get(0).getKey()).isEqualTo(node1);
              });
    }
  }

  @Test
  @UseDataProvider("failureAndIdempotent")
  public void should_ignore_error_if_idempotent_and_retry_policy_decides_so(
      FailureScenario failureScenario, boolean defaultIdempotence, SimpleStatement statement) {
    RequestHandlerTestHarness.Builder harnessBuilder =
        RequestHandlerTestHarness.builder().withDefaultIdempotence(defaultIdempotence);
    failureScenario.mockRequestError(harnessBuilder, node1);

    try (RequestHandlerTestHarness harness = harnessBuilder.build()) {
      failureScenario.mockRetryPolicyDecision(
          harness.getContext().retryPolicy(), RetryDecision.IGNORE);

      CompletionStage<AsyncResultSet> resultSetFuture =
          new CqlRequestAsyncHandler(statement, harness.getSession(), harness.getContext(), "test")
              .handle();

      assertThat(resultSetFuture)
          .isSuccess(
              resultSet -> {
                Iterator<Row> rows = resultSet.currentPage().iterator();
                assertThat(rows.hasNext()).isFalse();

                ExecutionInfo executionInfo = resultSet.getExecutionInfo();
                assertThat(executionInfo.getCoordinator()).isEqualTo(node1);
                assertThat(executionInfo.getErrors()).hasSize(0);
              });
    }
  }

  @Test
  @UseDataProvider("failureAndIdempotent")
  public void should_rethrow_error_if_idempotent_and_retry_policy_decides_so(
      FailureScenario failureScenario, boolean defaultIdempotence, SimpleStatement statement) {
    RequestHandlerTestHarness.Builder harnessBuilder =
        RequestHandlerTestHarness.builder().withDefaultIdempotence(defaultIdempotence);
    failureScenario.mockRequestError(harnessBuilder, node1);

    try (RequestHandlerTestHarness harness = harnessBuilder.build()) {

      failureScenario.mockRetryPolicyDecision(
          harness.getContext().retryPolicy(), RetryDecision.RETHROW);

      CompletionStage<AsyncResultSet> resultSetFuture =
          new CqlRequestAsyncHandler(statement, harness.getSession(), harness.getContext(), "test")
              .handle();

      assertThat(resultSetFuture)
          .isFailed(
              error -> assertThat(error).isInstanceOf(failureScenario.expectedExceptionClass));
    }
  }

  @Test
  @UseDataProvider("failureAndNotIdempotent")
  public void should_rethrow_error_if_not_idempotent_and_error_unsafe_or_policy_rethrows(
      FailureScenario failureScenario, boolean defaultIdempotence, SimpleStatement statement) {

    // For two of the possible exceptions, the retry policy is called even if the statement is not
    // idempotent
    boolean shouldCallRetryPolicy =
        (failureScenario.expectedExceptionClass.equals(UnavailableException.class)
            || failureScenario.expectedExceptionClass.equals(ReadTimeoutException.class));

    RequestHandlerTestHarness.Builder harnessBuilder =
        RequestHandlerTestHarness.builder().withDefaultIdempotence(defaultIdempotence);
    failureScenario.mockRequestError(harnessBuilder, node1);
    harnessBuilder.withResponse(node2, defaultFrameOf(singleRow()));

    try (RequestHandlerTestHarness harness = harnessBuilder.build()) {

      if (shouldCallRetryPolicy) {
        failureScenario.mockRetryPolicyDecision(
            harness.getContext().retryPolicy(), RetryDecision.RETHROW);
      }

      CompletionStage<AsyncResultSet> resultSetFuture =
          new CqlRequestAsyncHandler(statement, harness.getSession(), harness.getContext(), "test")
              .handle();

      assertThat(resultSetFuture)
          .isFailed(
              error -> {
                assertThat(error).isInstanceOf(failureScenario.expectedExceptionClass);
                // When non idempotent, the policy is bypassed completely:
                if (!shouldCallRetryPolicy) {
                  Mockito.verifyNoMoreInteractions(harness.getContext().retryPolicy());
                }
              });
    }
  }

  /**
   * Sets up the mocks to simulate an error from a node, and make the retry policy return a given
   * decision for that error.
   */
  private abstract static class FailureScenario {
    private final Class<? extends Throwable> expectedExceptionClass;

    protected FailureScenario(Class<? extends Throwable> expectedExceptionClass) {
      this.expectedExceptionClass = expectedExceptionClass;
    }

    abstract void mockRequestError(RequestHandlerTestHarness.Builder builder, Node node);

    abstract void mockRetryPolicyDecision(RetryPolicy policy, RetryDecision decision);
  }

  @DataProvider
  public static Object[][] failure() {
    return TestDataProviders.fromList(
        new FailureScenario(ReadTimeoutException.class) {
          @Override
          public void mockRequestError(RequestHandlerTestHarness.Builder builder, Node node) {
            builder.withResponse(
                node,
                defaultFrameOf(
                    new ReadTimeout(
                        "mock message", ProtocolConstants.ConsistencyLevel.LOCAL_ONE, 1, 2, true)));
          }

          @Override
          public void mockRetryPolicyDecision(RetryPolicy policy, RetryDecision decision) {
            Mockito.when(
                    policy.onReadTimeout(
                        any(SimpleStatement.class),
                        eq(CoreConsistencyLevel.LOCAL_ONE),
                        eq(2),
                        eq(1),
                        eq(true),
                        eq(0)))
                .thenReturn(decision);
          }
        },
        new FailureScenario(WriteTimeoutException.class) {
          @Override
          public void mockRequestError(RequestHandlerTestHarness.Builder builder, Node node) {
            builder.withResponse(
                node,
                defaultFrameOf(
                    new WriteTimeout(
                        "mock message",
                        ProtocolConstants.ConsistencyLevel.LOCAL_ONE,
                        1,
                        2,
                        ProtocolConstants.WriteType.SIMPLE)));
          }

          @Override
          public void mockRetryPolicyDecision(RetryPolicy policy, RetryDecision decision) {
            Mockito.when(
                    policy.onWriteTimeout(
                        any(SimpleStatement.class),
                        eq(CoreConsistencyLevel.LOCAL_ONE),
                        eq(CoreWriteType.SIMPLE),
                        eq(2),
                        eq(1),
                        eq(0)))
                .thenReturn(decision);
          }
        },
        new FailureScenario(UnavailableException.class) {
          @Override
          public void mockRequestError(RequestHandlerTestHarness.Builder builder, Node node) {
            builder.withResponse(
                node,
                defaultFrameOf(
                    new Unavailable(
                        "mock message", ProtocolConstants.ConsistencyLevel.LOCAL_ONE, 2, 1)));
          }

          @Override
          public void mockRetryPolicyDecision(RetryPolicy policy, RetryDecision decision) {
            Mockito.when(
                    policy.onUnavailable(
                        any(SimpleStatement.class),
                        eq(CoreConsistencyLevel.LOCAL_ONE),
                        eq(2),
                        eq(1),
                        eq(0)))
                .thenReturn(decision);
          }
        },
        new FailureScenario(ServerError.class) {
          @Override
          public void mockRequestError(RequestHandlerTestHarness.Builder builder, Node node) {
            builder.withResponse(
                node,
                defaultFrameOf(
                    new Error(ProtocolConstants.ErrorCode.SERVER_ERROR, "mock server error")));
          }

          @Override
          public void mockRetryPolicyDecision(RetryPolicy policy, RetryDecision decision) {
            Mockito.when(
                    policy.onErrorResponse(
                        any(SimpleStatement.class), any(ServerError.class), eq(0)))
                .thenReturn(decision);
          }
        },
        new FailureScenario(HeartbeatException.class) {
          @Override
          public void mockRequestError(RequestHandlerTestHarness.Builder builder, Node node) {
            builder.withResponseFailure(node, Mockito.mock(HeartbeatException.class));
          }

          @Override
          public void mockRetryPolicyDecision(RetryPolicy policy, RetryDecision decision) {
            Mockito.when(
                    policy.onRequestAborted(
                        any(SimpleStatement.class), any(HeartbeatException.class), eq(0)))
                .thenReturn(decision);
          }
        });
  }

  @DataProvider
  public static Object[][] failureAndIdempotent() {
    return TestDataProviders.combine(failure(), idempotentConfig());
  }

  @DataProvider
  public static Object[][] failureAndNotIdempotent() {
    return TestDataProviders.combine(failure(), nonIdempotentConfig());
  }
}
