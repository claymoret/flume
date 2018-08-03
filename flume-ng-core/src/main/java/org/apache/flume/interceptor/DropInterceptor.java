/**
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

package org.apache.flume.interceptor;

import com.google.common.collect.Lists;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.flume.interceptor.DropInterceptor.Constants.*;

/**
 * Drop events according to headers or body.
 */
public class DropInterceptor implements Interceptor {
  private static final Logger logger = LoggerFactory
          .getLogger(DropInterceptor.class);
  private final boolean dropEmptyLine;
  private List<String> headerNeeded = new ArrayList<String>();


  /**
   * Only {@link DropInterceptor.Builder} can build me
   */
  private DropInterceptor(boolean dropEmptyLine, String dropIfHeaderNotExist) {
    this.dropEmptyLine = dropEmptyLine;
    if (dropIfHeaderNotExist != null) {
      String [] headers = dropIfHeaderNotExist.split(",", -1);
      for(String h: headers) {
        String header = h.trim();
        if (header.length() > 0) {
          headerNeeded.add(header);
          logger.info("header [{}] must exist.", header);
        }
      }
    }
  }

  @Override
  public void initialize() {
    // no-op
  }

  /**
   * Modifies events in-place.
   */
  @Override
  public Event intercept(Event event) {
    Map<String, String> headers = event.getHeaders();
    String log = new String(event.getBody());
    if (dropEmptyLine && log.isEmpty()) {
      logger.info("drop empty line");
      return null;
    }

    for (String header : headerNeeded) {
      if (!headers.containsKey(header)) {
        logger.info("header {} do not exist, drop event: [{}]", header, log);
        return null;
      }
    }

    return event;
  }

  /**
   * Delegates to {@link #intercept(Event)} in a loop.
   * @param events
   * @return
   */
  @Override
  public List<Event> intercept(List<Event> events) {
    List<Event> intercepted = Lists.newArrayListWithCapacity(events.size());
    for (Event event : events) {
      Event interceptedEvent = intercept(event);
      if (interceptedEvent != null) {
        intercepted.add(interceptedEvent);
      }
    }
    return intercepted;
  }

  @Override
  public void close() {
    // no-op
  }

  /**
   * Builder which builds new instances of the TimestampInterceptor.
   */
  public static class Builder implements Interceptor.Builder {

    private boolean dropEmptyLine = true;
    private String dropIfHeaderNotExist;

    @Override
    public Interceptor build() {
      return new DropInterceptor(dropEmptyLine, dropIfHeaderNotExist);
    }

    @Override
    public void configure(Context context) {
      dropEmptyLine = context.getBoolean(DROP_EMPTY_LINE, true);
      dropIfHeaderNotExist = context.getString(DROP_IF_HEADER_NOT_EXIST);
    }

  }

  public static class Constants {
    public static String DROP_EMPTY_LINE = "dropEmptyLine";
    public static String DROP_IF_HEADER_NOT_EXIST = "dropIfHeaderNotExist";
  }

}
