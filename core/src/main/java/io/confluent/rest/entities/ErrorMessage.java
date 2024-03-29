/*
 * Copyright 2014 Confluent Inc.
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

package io.confluent.rest.entities;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Generic JSON error message.
 */
public class ErrorMessage {

  private int errorCode;
  private String message;

  public ErrorMessage(
      @JsonProperty("error_code") int errorCode,
      @JsonProperty("message") String message
  ) {
    this.errorCode = errorCode;
    this.message = message;
  }

  @JsonProperty("error_code")
  public int getErrorCode() {
    return errorCode;
  }

  @JsonProperty("error_code")
  public void setErrorCode(int errorCode) {
    this.errorCode = errorCode;
  }

  @JsonProperty
  public String getMessage() {
    return message;
  }

  @JsonProperty
  public void setMessage(String message) {
    this.message = message;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ErrorMessage that = (ErrorMessage) o;
    return errorCode == that.errorCode
            && Objects.equals(message, that.message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(errorCode, message);
  }
}
