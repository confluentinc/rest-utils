/*
 * Copyright 2015 Confluent Inc.
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

package io.confluent.rest.exceptions;

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

/**
 * ConstraintViolationException that includes RestException-like data to create a standard error
 * response. Note that this does not inherit from RestException because it must inherit from
 * ConstraintViolationException to be caught by the correct ExceptionMapper.
 */
public class RestConstraintViolationException extends ConstraintViolationException {

  public static final int DEFAULT_ERROR_CODE = 
      ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY_CODE;
  private int errorCode;

  public RestConstraintViolationException(String message, int errorCode) {
    this(message, errorCode, null);
  }

  public RestConstraintViolationException(String message, int errorCode,
                                          Set<? extends ConstraintViolation<?>>
                                              constraintViolations) {
    super(message, constraintViolations);
    this.errorCode = errorCode;
  }

  public int getStatus() {
    return ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY_CODE;
  }

  public int getErrorCode() {
    return errorCode;
  }
}
