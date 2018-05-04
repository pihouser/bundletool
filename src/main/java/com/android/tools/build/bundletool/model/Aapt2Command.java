/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.model;

import com.android.tools.build.bundletool.exceptions.CommandExecutionException;
import com.android.tools.build.bundletool.utils.files.BufferedIo;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Exposes aapt2 commands used by Bundle Tool. */
public interface Aapt2Command {

  void convertApkProtoToBinary(Path protoApk, Path binaryApk);

  static Aapt2Command createFromExecutablePath(Path aapt2Path) {
    return new Aapt2Command() {
      @Override
      public void convertApkProtoToBinary(Path protoApk, Path binaryApk) {
        new CommandExecutor()
            .execute(
                aapt2Path.toString(),
                "convert",
                "--output-format",
                "binary",
                "-o",
                binaryApk.toString(),
                protoApk.toString());
      }
    };
  }

  /** Helper to execute aapt2 commands. */
  class CommandExecutor {
    private static final int TIMEOUT_AAPT2_COMMANDS_SECONDS = 5 * 60; // 5 minutes.

    void execute(String... command) {
      try {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(TIMEOUT_AAPT2_COMMANDS_SECONDS, TimeUnit.SECONDS)) {
          printOutput(process);
          throw new CommandExecutionException("Command timed out: " + Arrays.toString(command));
        }
        if (process.exitValue() != 0) {
          printOutput(process);
          throw new CommandExecutionException(
              String.format(
                  "Command '%s' didn't terminate successfully (exit code: %d). Check the logs.",
                  Arrays.toString(command), process.exitValue()));
        }
      } catch (IOException | InterruptedException e) {
        throw CommandExecutionException.builder()
            .withCause(e)
            .withMessage("Error when executing 'aapt2 convert' command.")
            .build();
      }
    }

    private static void printOutput(Process process) {
      try (BufferedReader outputReader = BufferedIo.reader(process.getInputStream())) {
        String line;
        while ((line = outputReader.readLine()) != null) {
          System.err.println(line);
        }
      } catch (IOException e) {
        // Ignore.
      }
    }
  }
}
