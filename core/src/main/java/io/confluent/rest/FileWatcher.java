/*
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
 */
 
package io.confluent.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.nio.file.WatchEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

// reference https://gist.github.com/danielflower/f54c2fe42d32356301c68860a4ab21ed
public class FileWatcher implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);
  private static final ExecutorService executor = Executors.newFixedThreadPool(1,
        new ThreadFactory() {
          public Thread newThread(Runnable r) {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
          }
        });

  public interface Callback {
    void run() throws Exception;
  }

  private volatile boolean shutdown;
  private final WatchService watchService;
  private final Path file;
  private final Callback callback;

  public FileWatcher(Path file, Callback callback) throws IOException {
    this.file = file;
    this.watchService = FileSystems.getDefault().newWatchService();

    // This uses k8s secrets, and the way they are updated is by writing them to a new directory,
    // creating a symlink to it, then renaming it to DATA_DIR_NAME. So we only watch for that
    // event, which should be the last one caused by an update.
    file.getParent().register(watchService,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.OVERFLOW);
    this.callback = callback;
  }

  /**
    * Starts watching a file calls the callback when it is changed.
    * A shutdown hook is registered to stop watching.
  */
  public static void onFileChange(Path file, Callback callback) throws IOException {
    log.info("Constructing a new watch service: " + file);
    FileWatcher fileWatcher = new FileWatcher(file, callback);
    executor.submit(fileWatcher);
  }

  public void run() {
    try {
      while (!shutdown) {
        try {
          handleNextWatchNotification();
        } catch (InterruptedException e) {
          throw e;
        } catch (ClosedWatchServiceException e) {
          shutdown = true;
        } catch (Exception e) {
          log.info("Watch service caught exception, will continue:" + e);
        }
      }
    } catch (InterruptedException e) {
      log.info("Ending watch due to interrupt");
    }
  }

  private void handleNextWatchNotification() throws InterruptedException {
    log.debug("Waiting for watch key to be signalled: " + file);

    // wait for key to be signalled
    WatchKey key = watchService.take();
    log.info("Watch key signalled");

    boolean runCallback = false;
    for (WatchEvent<?> event : key.pollEvents()) {
      WatchEvent.Kind<?> kind = event.kind();
      if (kind == StandardWatchEventKinds.OVERFLOW) {
        log.debug("Watch event is OVERFLOW");
        continue;
      }

      if (event.context() != null && !(event.context() instanceof Path)) {
        throw new ClassCastException("Expected `event.context()` to be an instance of " + Path.class
            + ", but it is " + event.context().getClass());
      }

      Path context = (Path) event.context();
      Path changed = this.file.getParent().resolve(context);
      log.info("Watch event is " + event.kind() + ": " + context + " => " + changed);

      if (changed.equals(this.file)) {
        if (Files.exists(changed)) {
          log.debug("Watch resolved path exists: " + file);
          runCallback = true;
        } else {
          log.debug("Watch resolved path does not exist: " + file);
        }
      } else {
        log.debug("Watch resolved path is not the same");
      }
    }

    key.reset();

    if (runCallback) {
      try {
        callback.run();
      } catch (Exception e) {
        log.warn("Hit exception in callback on file watcher", e);
      }
    }
  }

  public void shutdown() {
    shutdown = true;
    try {
      watchService.close();
    } catch (IOException e) {
      log.info("Error closing watch service", e);
    }
  }

}
