/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.source.taildir;

import static org.apache.flume.source.taildir.TaildirSourceConfigurationConstants.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;

public class TailFile {
  private static final Logger logger = LoggerFactory.getLogger(TailFile.class);

  private static final String LINE_SEP = "\n";
  private static final String LINE_SEP_WIN = "\r\n";

  private RandomAccessFile raf;
  private String path;
  private final long inode;
  private long pos;
  private long lastUpdated;
  private boolean needTail;
  private final Map<String, String> headers;
  private byte[] buffer;
  private int bufferPos;
  private int bufferLen;
  private StringBuilder lineBuilder;
  private long lineReadPos;

  public TailFile(File file, Map<String, String> headers, long inode, long pos)
      throws IOException {
    this.raf = new RandomAccessFile(file, "r");
    if (pos > 0) {
      raf.seek(pos);
      lineReadPos = pos;
    }
    this.path = file.getAbsolutePath();
    this.inode = inode;
    this.pos = pos;
    this.lastUpdated = 0L;
    this.needTail = true;
    this.headers = headers;
    this.buffer = new byte[8192];
    this.bufferPos = -1;
    this.bufferLen = -1;
    this.lineBuilder = null;
  }

  public RandomAccessFile getRaf() { return raf; }
  public String getPath() { return path; }
  public long getInode() { return inode; }
  public long getPos() { return pos; }
  public long getLineReadPos() { return lineReadPos; }
  public long getLastUpdated() { return lastUpdated; }
  public boolean needTail() { return needTail; }
  public Map<String, String> getHeaders() { return headers; }

  public void setPath(String path) { this.path = path; }
  public void setPos(long pos) { this.pos = pos; }
  public void setLineReadPos(long lineReadPos) {
    logger.debug("Updated line read position from: " + this.lineReadPos + " to: " + lineReadPos);
    this.lineReadPos = lineReadPos;
  }
  public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
  public void setNeedTail(boolean needTail) { this.needTail = needTail; }

  public boolean updatePos(String path, long inode, long pos) throws IOException {
    if (this.inode == inode) {
      updateFilePos(pos);
      setPos(pos);
      logger.info("Updated position, file: " + path + ", inode: " + inode + ", pos: " + pos);
      return true;
    }
    return false;
  }

  public void updateFilePos(long pos) throws IOException {
    raf.seek(pos);
    lineReadPos = pos;
    bufferPos = -1;
    bufferLen = -1;
    lineBuilder = null;
  }

  public List<Event> readEvents(int numEvents, boolean backoffWithoutNL,
      boolean addByteOffset) throws IOException {
    List<Event> events = Lists.newLinkedList();
    for (int i = 0; i < numEvents; i++) {
      Event event = readEvent(backoffWithoutNL, addByteOffset);
      if (event == null) {
        break;
      }
      events.add(event);
    }
    return events;
  }

  private Event readEvent(boolean backoffWithoutNL, boolean addByteOffset) throws IOException {
    Long posTmp = getLineReadPos();
    String line = readLine();
    if (line == null) {
      return null;
    }
    if (backoffWithoutNL && !line.endsWith(LINE_SEP)) {
      logger.info("Backing off in file without newline: "
          + path + ", inode: " + inode + ", pos: " + raf.getFilePointer());
      updateFilePos(posTmp);
      return null;
    }

    String lineSep = LINE_SEP;
    if(line.endsWith(LINE_SEP_WIN)) {
      lineSep = LINE_SEP_WIN;
    }
    Event event = EventBuilder.withBody(StringUtils.removeEnd(line, lineSep), Charsets.UTF_8);
    if (addByteOffset == true) {
      event.getHeaders().put(BYTE_OFFSET_HEADER_KEY, posTmp.toString());
    }
    return event;
  }

  private String readLine() throws IOException {
    String line = null;
    if (lineBuilder == null) {
      lineBuilder = new StringBuilder(1024);
    }
    while (true) {
      if (bufferPos == -1) {
        if (raf.getFilePointer() < raf.length()) {
          bufferLen = raf.read(buffer);
          bufferPos = 0;
        } else {
          if (lineBuilder.length() > 0) {
            line = lineBuilder.toString();
            setLineReadPos(lineReadPos + line.length());
            lineBuilder = null;
          }
          break;
        }
      }
      for (; bufferPos < bufferLen;) {
        lineBuilder.append((char)buffer[bufferPos]);
        if (buffer[bufferPos++] == LINE_SEP.charAt(0)) {
          line = lineBuilder.toString();
          setLineReadPos(lineReadPos + line.length());
          lineBuilder = null;
          break;
        }
      }
      if (bufferPos == bufferLen) {
        bufferPos = -1;
        bufferLen = -1;
      }
      if (line != null) {
        break;
      }
    }
    return line;
  }

  public void close() {
    try {
      raf.close();
      raf = null;
      long now = System.currentTimeMillis();
      setLastUpdated(now);
    } catch (IOException e) {
      logger.error("Failed closing file: " + path + ", inode: " + inode, e);
    }
  }

  public static class CompareByLastModifiedTime implements Comparator<File> {
    @Override
    public int compare(File f1, File f2) {
      return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());
    }
  }


}
