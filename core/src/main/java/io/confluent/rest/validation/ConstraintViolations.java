/**
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

package io.confluent.rest.validation;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

import java.util.HashSet;
import java.util.Set;

public class ConstraintViolations {

  private ConstraintViolations() { /* singleton */ }

  public static <T> String format(ConstraintViolation<T> v) {
    return String.format("%s %s (was %s)",
                         v.getPropertyPath(),
                         v.getMessage(),
                         v.getInvalidValue());
  }

  public static String formatUntyped(Set<ConstraintViolation<?>> violations) {
    StringBuilder builder = new StringBuilder();
    boolean first = true;
    for (ConstraintViolation<?> v : violations) {
      if (!first) {
        builder.append("; ");
      }
      builder.append(format(v));
      first = false;
    }
    return builder.toString();
  }

  public static ConstraintViolationException simpleException(String msg) {
    return new ConstraintViolationException(msg, new HashSet<ConstraintViolation<?>>());
  }
}
