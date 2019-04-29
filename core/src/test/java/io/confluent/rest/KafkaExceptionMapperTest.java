/**
 * Copyright 2019 Confluent Inc.
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
 **/

package io.confluent.rest;

import io.confluent.rest.entities.ErrorMessage;
import io.confluent.rest.exceptions.KafkaExceptionMapper;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.BrokerNotAvailableException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.ConcurrentTransactionsException;
import org.apache.kafka.common.errors.DelegationTokenAuthorizationException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.NotCoordinatorException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SecurityDisabledException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.TransactionalIdAuthorizationException;
import org.apache.kafka.common.errors.UnknownServerException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.util.concurrent.ExecutionException;

import static io.confluent.rest.exceptions.KafkaExceptionMapper.BROKER_NOT_AVAILABLE_ERROR_CODE;
import static io.confluent.rest.exceptions.KafkaExceptionMapper.KAFKA_AUTHENTICATION_ERROR_CODE;
import static io.confluent.rest.exceptions.KafkaExceptionMapper.KAFKA_AUTHORIZATION_ERROR_CODE;
import static io.confluent.rest.exceptions.KafkaExceptionMapper.KAFKA_BAD_REQUEST_ERROR_CODE;
import static io.confluent.rest.exceptions.KafkaExceptionMapper.KAFKA_ERROR_ERROR_CODE;
import static io.confluent.rest.exceptions.KafkaExceptionMapper.KAFKA_RETRIABLE_ERROR_ERROR_CODE;
import static io.confluent.rest.exceptions.KafkaExceptionMapper.KAFKA_UNKNOWN_TOPIC_PARTITION_CODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class KafkaExceptionMapperTest {

  private KafkaExceptionMapper exceptionMapper;

  @Before
  public void setUp() {
    exceptionMapper = new KafkaExceptionMapper(null);
  }

  @Test
  public void testAuthenticationExceptions() {
    verifyMapperResponse(new AuthenticationException("some message"), Status.UNAUTHORIZED,
        KAFKA_AUTHENTICATION_ERROR_CODE);
    verifyMapperResponse(new SaslAuthenticationException("some message"), Status.UNAUTHORIZED,
        KAFKA_AUTHENTICATION_ERROR_CODE);
  }

  @Test
  public void testAuthorizationExceptions() {
    verifyMapperResponse(new AuthorizationException("some message"), Status.FORBIDDEN,
        KAFKA_AUTHORIZATION_ERROR_CODE);
    verifyMapperResponse(new ClusterAuthorizationException("some message"), Status.FORBIDDEN,
        KAFKA_AUTHORIZATION_ERROR_CODE);
    verifyMapperResponse(new DelegationTokenAuthorizationException("some message"), Status.FORBIDDEN,
        KAFKA_AUTHORIZATION_ERROR_CODE);
    verifyMapperResponse(new GroupAuthorizationException("some message"), Status.FORBIDDEN,
        KAFKA_AUTHORIZATION_ERROR_CODE);
    verifyMapperResponse(new TopicAuthorizationException("some message"), Status.FORBIDDEN,
        KAFKA_AUTHORIZATION_ERROR_CODE);
    verifyMapperResponse(new TransactionalIdAuthorizationException("some message"), Status.FORBIDDEN,
        KAFKA_AUTHORIZATION_ERROR_CODE);
  }

  @Test
  public void testKafkaExceptions() {
    //exceptions mapped in KafkaExceptionMapper
    verifyMapperResponse(new BrokerNotAvailableException("some message"), Status.SERVICE_UNAVAILABLE,
        BROKER_NOT_AVAILABLE_ERROR_CODE);

    verifyMapperResponse(new InvalidReplicationFactorException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new SecurityDisabledException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new UnsupportedVersionException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new InvalidPartitionsException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new InvalidRequestException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new UnknownServerException("some message"),Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new UnknownTopicOrPartitionException("some message"), Status.NOT_FOUND,
        KAFKA_UNKNOWN_TOPIC_PARTITION_CODE);
    verifyMapperResponse(new PolicyViolationException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new TopicExistsException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);
    verifyMapperResponse(new InvalidConfigurationException("some message"), Status.BAD_REQUEST,
        KAFKA_BAD_REQUEST_ERROR_CODE);

    //test couple of retriable exceptions
    verifyMapperResponse(new NotCoordinatorException("some message"), Status.INTERNAL_SERVER_ERROR,
        KAFKA_RETRIABLE_ERROR_ERROR_CODE);
    verifyMapperResponse(new NotEnoughReplicasException("some message"), Status.INTERNAL_SERVER_ERROR,
        KAFKA_RETRIABLE_ERROR_ERROR_CODE);

    //test couple of kafka exception
    verifyMapperResponse(new CommitFailedException(), Status.INTERNAL_SERVER_ERROR,
        KAFKA_ERROR_ERROR_CODE);
    verifyMapperResponse(new ConcurrentTransactionsException("some message"), Status.INTERNAL_SERVER_ERROR,
        KAFKA_ERROR_ERROR_CODE);

    //test few general exceptions
    verifyMapperResponse(new NullPointerException("some message"), Status.INTERNAL_SERVER_ERROR,
        Status.INTERNAL_SERVER_ERROR.getStatusCode());
    verifyMapperResponse(new IllegalArgumentException("some message"), Status.INTERNAL_SERVER_ERROR,
        Status.INTERNAL_SERVER_ERROR.getStatusCode());
  }

  private void verifyMapperResponse(Throwable throwable, Status status, int errorCode) {
    Response response = exceptionMapper.toResponse(new ExecutionException("whats this then", throwable));
    assertNotNull(response);
    assertEquals(status.getStatusCode(), response.getStatus());
    ErrorMessage errorMessage = (ErrorMessage) response.getEntity();
    assertEquals(errorCode, errorMessage.getErrorCode());
  }
}
