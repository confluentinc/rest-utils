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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.nio.file.WatchEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// reference https://gist.github.com/danielflower/f54c2fe42d32356301c68860a4ab21ed
public class FileWatcher implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

  public interface Callback {
    void run() throws Exception;
  }

  private volatile boolean shutdown;
  private final WatchService watchService;
  private final Path file;
  private final Callback callback;
  private final ExecutorService executorService = Executors.newSingleThreadExecutor(
      r -> {
        Thread thread = new Thread(r, "file-watcher");
        thread.setDaemon(true);
        return thread;
      });

  public FileWatcher(Path file, Callback callback) throws IOException {
    this.file = file;
    this.watchService = FileSystems.getDefault().newWatchService();
    // Listen to both CREATE and MODIFY to reload, so taking care of delete then create.
    file.getParent().register(watchService,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY);
    this.callback = callback;
  }

  /**
    * Starts watching a file calls the callback when it is changed.
    * A shutdown hook is registered to stop watching.
  */
  public static void onFileChange(Path file, Callback callback) throws IOException {
    log.info("Configure watch file change: " + file);
    FileWatcher fileWatcher = new FileWatcher(file, callback);
    fileWatcher.executorService.submit(fileWatcher);
  }

  public void run() {
    log.info("Running file watcher service thread");
    try {
      while (!shutdown) {
        try {
          handleNextWatchNotification();
        } catch (InterruptedException e) {
          throw e;
        } catch (Exception e) {
          log.info("Watch service caught exception, will continue:" + e);
        }
      }
    } catch (InterruptedException e) {
      log.info("Ending watch due to interrupt");
    }
  }

  private void handleNextWatchNotification() throws InterruptedException {
    log.debug("Watching file change: " + file);
    // wait for key to be signalled
    WatchKey key = watchService.take();
    log.info("Watch Key notified");
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
      log.info("Watch file change: " + context + "=>" + changed);
      // Need to use path equals than isSameFile
      if (Files.exists(changed) && changed.equals(this.file)) {
        log.debug("Watch matching file: " + file);
        try {
          callback.run();
        } catch (Exception e) {
          log.warn("Hit error callback on file change", e);
        }
        break;
      }
    }
    key.reset();
  }

  public void shutdown() {
    shutdown = true;
    try {
      watchService.close();
      executorService.shutdown();
    } catch (IOException e) {
      log.info("Error closing watch service", e);
    }
  }

}
