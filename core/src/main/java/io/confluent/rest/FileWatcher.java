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

// reference https://gist.github.com/danielflower/f54c2fe42d32356301c68860a4ab21ed
public class FileWatcher {
  private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

  private Thread thread;
  private WatchService watchService;

  public interface Callback {
    void run() throws Exception;
  }

  /**
    * Starts watching a file and the given path and calls the callback when it is changed.
    * A shutdown hook is registered to stop watching. To control this yourself, create an
    * instance and use the start/stop methods.
  */
  public static void onFileChange(Path file, Callback callback) throws IOException {
    FileWatcher fileWatcher = new FileWatcher();
    fileWatcher.start(file, callback);
    Runtime.getRuntime().addShutdownHook(new Thread(fileWatcher::stop));
  }

  public void start(Path file, Callback callback) throws IOException {
    watchService = FileSystems.getDefault().newWatchService();
    Path parent = file.getParent();
    parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
    log.info("Going to watch " + file);

    thread = new Thread(() -> {
      while (true) {
        WatchKey wk = null;
        try {
          wk = watchService.take();
          Thread.sleep(500); // give a chance for duplicate events to pile up
          for (WatchEvent<?> event : wk.pollEvents()) {
            Path changed = parent.resolve((Path) event.context());
            if (Files.exists(changed) && Files.isSameFile(changed, file)) {
              log.info("File change event: " + changed);
              callback.run();
              break;
            }
          }
        } catch (InterruptedException e) {
          log.info("Ending my watch");
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          log.error("Error while reloading cert", e);
        } finally {
          if (wk != null) {
            wk.reset();
          }
        }
      }
    });
    thread.start();
  }

  public void stop() {
    thread.interrupt();
    try {
      watchService.close();
    } catch (IOException e) {
      log.info("Error closing watch service", e);
    }
  }
}