/**
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
 **/

// This is a backport of the Jetty 9 Slf4jRequestLog class to work with Jetty 8. The
// following is the original copyright notice:
//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package io.confluent.rest.logging;

import java.io.IOException;

import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.util.log.Slf4jLog;

/**
 * Implementation of NCSARequestLog where output is sent as a SLF4J INFO Log message on the named logger "org.eclipse.jetty.server.RequestLog"
 */
public class Slf4jRequestLog extends AbstractNCSARequestLog implements RequestLog
{
  private Slf4jLog logger;
  private String loggerName;

  public Slf4jRequestLog()
  {
    // Default logger name (can be set)
    this.loggerName = "org.eclipse.jetty.server.RequestLog";
  }

  public void setLoggerName(String loggerName)
  {
    this.loggerName = loggerName;
  }

  public String getLoggerName()
  {
    return loggerName;
  }

  @Override
  protected boolean isEnabled()
  {
    return logger != null;
  }

  @Override
  public void write(String requestEntry) throws IOException
  {
    logger.info(requestEntry);
  }

  @Override
  protected synchronized void doStart() throws Exception
  {
    logger = new Slf4jLog(loggerName);
    super.doStart();
  }
}
