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
import java.util.concurrent.Future;

// reference https://gist.github.com/danielflower/f54c2fe42d32356301c68860a4ab21ed
public class FileWatcher implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

  public interface Callback {
    void run() throws Exception;
  }

  private final WatchService watchService;
  private final Path file;
  private final WatchKey key;
  private final Callback callback;

  public FileWatcher(Path file, Callback callback) throws IOException {
    this.file = file;
    this.watchService = FileSystems.getDefault().newWatchService();
    this.key = file.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
    this.callback = callback;
  }

  /**
    * Starts watching a file and the given path and calls the callback when it is changed.
    * A shutdown hook is registered to stop watching. To control this yourself, create an
    * instance and use the start/stop methods.
  */
  public static void onFileChange(Path file, Callback callback) throws IOException {
    log.info("Configure watch file change: " + file);
    FileWatcher fileWatcher = new FileWatcher(file, callback);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> future = executor.submit(fileWatcher);
    Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdown));
  }

  public void run() {
    try {
      for (;;) {
        log.info("Watching file change: " + file);
        // wait for key to be signalled
        WatchKey key = watchService.take();
        if (this.key != key) {
          log.info("WatchKey not recognized");
          continue;
        }
        log.info("Watch Key notified");
        for (WatchEvent<?> event : key.pollEvents()) {
          WatchEvent.Kind<?> kind = event.kind();
          if (kind != StandardWatchEventKinds.ENTRY_MODIFY) {
            log.info("Watch event is not modified.");
            continue;
          }
          WatchEvent<Path> ev = (WatchEvent<Path>)event;
          Path changed = this.file.getParent().resolve(ev.context());
          try {
            if (Files.isSameFile(changed, this.file)) {
              log.info("Watch found file: " + file);
              try {
                callback.run();
              } catch (Exception e) {
                log.error("Error while reloading cert", e);
              }          
              break;
            }
            continue;
          } catch (java.io.IOException e) {
            log.info("Hit error process the change event", e);
          }
        }
        // reset key
        if (!key.reset()) {
          break;
        }
      }
    } catch (InterruptedException e) {
      log.info("Ending my watch");
    }
  }
}