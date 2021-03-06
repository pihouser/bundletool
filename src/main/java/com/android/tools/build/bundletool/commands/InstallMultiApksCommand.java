/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tools.build.bundletool.commands;

import static com.android.tools.build.bundletool.commands.CommandUtils.ANDROID_SERIAL_VARIABLE;
import static com.android.tools.build.bundletool.model.utils.ResultUtils.readTableOfContents;
import static com.android.tools.build.bundletool.model.utils.SdkToolsLocator.ANDROID_HOME_VARIABLE;
import static com.android.tools.build.bundletool.model.utils.SdkToolsLocator.SYSTEM_PATH_VARIABLE;
import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileExistsAndExecutable;
import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileExistsAndReadable;
import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileHasExtension;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static com.google.common.collect.Streams.stream;
import static java.util.function.Function.identity;

import com.android.bundle.Commands.BuildApksResult;
import com.android.bundle.Devices.DeviceSpec;
import com.android.tools.build.bundletool.commands.CommandHelp.CommandDescription;
import com.android.tools.build.bundletool.commands.CommandHelp.FlagDescription;
import com.android.tools.build.bundletool.device.AdbServer;
import com.android.tools.build.bundletool.device.AdbShellCommandTask;
import com.android.tools.build.bundletool.device.BadgingPackageNameParser;
import com.android.tools.build.bundletool.device.Device;
import com.android.tools.build.bundletool.device.DeviceAnalyzer;
import com.android.tools.build.bundletool.device.IncompatibleDeviceException;
import com.android.tools.build.bundletool.device.MultiPackagesInstaller;
import com.android.tools.build.bundletool.device.MultiPackagesInstaller.InstallableApk;
import com.android.tools.build.bundletool.device.PackagesParser;
import com.android.tools.build.bundletool.flags.Flag;
import com.android.tools.build.bundletool.flags.ParsedFlags;
import com.android.tools.build.bundletool.io.TempDirectory;
import com.android.tools.build.bundletool.model.Aapt2Command;
import com.android.tools.build.bundletool.model.ZipPath;
import com.android.tools.build.bundletool.model.exceptions.CommandExecutionException;
import com.android.tools.build.bundletool.model.utils.DefaultSystemEnvironmentProvider;
import com.android.tools.build.bundletool.model.utils.SystemEnvironmentProvider;
import com.android.tools.build.bundletool.model.utils.files.BufferedIo;
import com.google.auto.value.AutoValue;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs multiple APKs on a connected device atomically.
 *
 * <p>If any APKs fails to install, the entire installation fails.
 */
@AutoValue
public abstract class InstallMultiApksCommand {
  private static final Logger logger = Logger.getLogger(InstallMultiApksCommand.class.getName());

  public static final String COMMAND_NAME = "install-multi-apks";

  private static final Flag<Path> ADB_PATH_FLAG = Flag.path("adb");
  private static final Flag<ImmutableList<Path>> APKS_ARCHIVES_FLAG = Flag.pathList("apks");
  private static final Flag<Path> APKS_ARCHIVE_ZIP_FLAG = Flag.path("apks-zip");
  private static final Flag<String> DEVICE_ID_FLAG = Flag.string("device-id");
  private static final Flag<Boolean> ENABLE_ROLLBACK_FLAG = Flag.booleanFlag("enable-rollback");
  private static final Flag<Boolean> UPDATE_ONLY_FLAG = Flag.booleanFlag("update-only");
  private static final Flag<Boolean> NO_COMMIT_FLAG = Flag.booleanFlag("no-commit");
  private static final Flag<Path> AAPT2_PATH_FLAG = Flag.path("aapt2");

  private static final SystemEnvironmentProvider DEFAULT_PROVIDER =
      new DefaultSystemEnvironmentProvider();

  abstract Path getAdbPath();

  abstract Optional<Aapt2Command> getAapt2Command();

  abstract ImmutableList<Path> getApksArchivePaths();

  abstract Optional<Path> getApksArchiveZipPath();

  abstract Optional<String> getDeviceId();

  abstract boolean getEnableRollback();

  abstract boolean getUpdateOnly();

  abstract boolean getNoCommitMode();

  abstract AdbServer getAdbServer();

  public static Builder builder() {
    return new AutoValue_InstallMultiApksCommand.Builder()
        .setEnableRollback(false)
        .setNoCommitMode(false)
        .setUpdateOnly(false);
  }

  /** Builder for the {@link InstallMultiApksCommand}. */
  @AutoValue.Builder
  public abstract static class Builder {
    abstract Builder setAdbPath(Path adbPath);

    @CanIgnoreReturnValue
    abstract Builder setAapt2Command(Aapt2Command value);

    @CanIgnoreReturnValue
    abstract Builder setApksArchivePaths(ImmutableList<Path> paths);

    abstract ImmutableList.Builder<Path> apksArchivePathsBuilder();

    @CanIgnoreReturnValue
    abstract Builder setApksArchiveZipPath(Path value);

    @CanIgnoreReturnValue
    Builder addApksArchivePath(Path value) {
      apksArchivePathsBuilder().add(value);
      return this;
    }

    @CanIgnoreReturnValue
    abstract Builder setDeviceId(String deviceId);

    abstract Builder setEnableRollback(boolean value);

    abstract Builder setUpdateOnly(boolean value);

    abstract Builder setNoCommitMode(boolean value);

    /** The caller is responsible for the lifecycle of the {@link AdbServer}. */
    abstract Builder setAdbServer(AdbServer adbServer);

    public abstract InstallMultiApksCommand build();
  }

  public static InstallMultiApksCommand fromFlags(ParsedFlags flags, AdbServer adbServer) {
    return fromFlags(flags, DEFAULT_PROVIDER, adbServer);
  }

  public static InstallMultiApksCommand fromFlags(
      ParsedFlags flags, SystemEnvironmentProvider systemEnvironmentProvider, AdbServer adbServer) {
    Path adbPath = CommandUtils.getAdbPath(flags, ADB_PATH_FLAG, systemEnvironmentProvider);

    InstallMultiApksCommand.Builder command = builder().setAdbPath(adbPath).setAdbServer(adbServer);
    CommandUtils.getDeviceSerialName(flags, DEVICE_ID_FLAG, systemEnvironmentProvider)
        .ifPresent(command::setDeviceId);
    ENABLE_ROLLBACK_FLAG.getValue(flags).ifPresent(command::setEnableRollback);
    UPDATE_ONLY_FLAG.getValue(flags).ifPresent(command::setUpdateOnly);
    NO_COMMIT_FLAG.getValue(flags).ifPresent(command::setNoCommitMode);
    AAPT2_PATH_FLAG
        .getValue(flags)
        .ifPresent(
            aapt2Path -> command.setAapt2Command(Aapt2Command.createFromExecutablePath(aapt2Path)));

    Optional<ImmutableList<Path>> apksPaths = APKS_ARCHIVES_FLAG.getValue(flags);
    Optional<Path> apksArchiveZip = APKS_ARCHIVE_ZIP_FLAG.getValue(flags);
    if (apksPaths.isPresent() == apksArchiveZip.isPresent()) {
      throw new CommandExecutionException("Exactly one of --apks or --apks-zip must be set.");
    }
    apksPaths.ifPresent(command::setApksArchivePaths);
    apksArchiveZip.ifPresent(command::setApksArchiveZipPath);

    flags.checkNoUnknownFlags();

    return command.build();
  }

  public void execute() throws TimeoutException, IOException {
    validateInput();

    AdbServer adbServer = getAdbServer();
    adbServer.init(getAdbPath());

    try (TempDirectory tempDirectory = new TempDirectory()) {
      DeviceAnalyzer deviceAnalyzer = new DeviceAnalyzer(adbServer);
      DeviceSpec deviceSpec = deviceAnalyzer.getDeviceSpec(getDeviceId());
      Device device = deviceAnalyzer.getAndValidateDevice(getDeviceId());

      MultiPackagesInstaller installer =
          new MultiPackagesInstaller(device, getEnableRollback(), getNoCommitMode());

      Path aapt2Dir = tempDirectory.getPath().resolve("aapt2");
      Files.createDirectory(aapt2Dir);
      Supplier<Aapt2Command> aapt2CommandSupplier =
          Suppliers.memoize(() -> getOrExtractAapt2Command(aapt2Dir));

      ImmutableSet<String> existingPackages =
          getUpdateOnly() ? listPackagesInstalledOnDevice(device) : ImmutableSet.of();

      ImmutableListMultimap<String, InstallableApk> apkToInstallByPackage =
          getActualApksPaths(tempDirectory).stream()
              .flatMap(
                  apksArchivePath ->
                      stream(
                          apksWithPackageName(apksArchivePath, deviceSpec, aapt2CommandSupplier)))
              .filter(apk -> !getUpdateOnly() || isInstalled(apk, existingPackages))
              .flatMap(apks -> extractApkListFromApks(deviceSpec, apks, tempDirectory).stream())
              .collect(toImmutableListMultimap(InstallableApk::getPackageName, identity()));

      if (apkToInstallByPackage.isEmpty()) {
        logger.warning("No packages found to install! Exiting...");
        return;
      }
      installer.install(apkToInstallByPackage);
    }
  }

  private static boolean isInstalled(InstallableApk apk, ImmutableSet<String> existingPackages) {
    boolean exist = existingPackages.contains(apk.getPackageName());
    if (!exist) {
      logger.info(
          String.format(
              "Package '%s' not present on device, skipping due to --%s.",
              apk.getPackageName(), UPDATE_ONLY_FLAG.getName()));
    }
    return exist;
  }

  private static ImmutableSet<String> listPackagesInstalledOnDevice(Device device) {
    ImmutableList<String> listPackagesOutput =
        new AdbShellCommandTask(device, "pm list packages").execute();
    return new PackagesParser().parse(listPackagesOutput);
  }

  private static Optional<InstallableApk> apksWithPackageName(
      Path apkArchivePath, DeviceSpec deviceSpec, Supplier<Aapt2Command> aapt2CommandSupplier) {
    BuildApksResult toc = readTableOfContents(apkArchivePath);
    if (toc.getPackageName().isEmpty()) {
      return getApksWithPackageNameFromAapt2(apkArchivePath, deviceSpec, aapt2CommandSupplier);
    }
    return Optional.of(InstallableApk.create(apkArchivePath, toc.getPackageName()));
  }

  private static Optional<InstallableApk> getApksWithPackageNameFromAapt2(
      Path apksArchivePath, DeviceSpec deviceSpec, Supplier<Aapt2Command> aapt2CommandSupplier) {
    try (TempDirectory tempDirectory = new TempDirectory()) {
      // Any of the extracted .apk/.apex files will work.
      Path extractedFile =
          ExtractApksCommand.builder()
              .setApksArchivePath(apksArchivePath)
              .setDeviceSpec(deviceSpec)
              .setOutputDirectory(tempDirectory.getPath())
              .build()
              .execute()
              .get(0);

      String packageName =
          BadgingPackageNameParser.parse(aapt2CommandSupplier.get().dumpBadging(extractedFile));
      return Optional.of(InstallableApk.create(apksArchivePath, packageName));
    } catch (IncompatibleDeviceException e) {
      logger.warning(
          String.format(
              "Unable to determine package name of %s, as it is not compatible with the attached"
                  + " device. Skipping.",
              apksArchivePath));
      return Optional.empty();
    }
  }

  private void validateInput() {
    getApksArchiveZipPath()
        .ifPresent(
            zip -> {
              checkFileExistsAndReadable(zip);
              checkFileHasExtension("ZIP file", zip, ".zip");
            });
    getApksArchivePaths().forEach(InstallMultiApksCommand::checkValidApksFile);
    checkFileExistsAndExecutable(getAdbPath());
  }

  private static void checkValidApksFile(Path path) {
    checkFileExistsAndReadable(path);
    checkFileHasExtension("APKS file", path, ".apks");
  }

  /** Extracts the apk/apex files that will be installed from a given .apks. */
  private static ImmutableList<InstallableApk> extractApkListFromApks(
      DeviceSpec deviceSpec, InstallableApk apksArchive, TempDirectory tempDirectory) {
    logger.info(String.format("Extracting package '%s'", apksArchive.getPackageName()));
    try {
      Path output = tempDirectory.getPath().resolve(apksArchive.getPackageName());
      Files.createDirectory(output);

      ExtractApksCommand.Builder extractApksCommand =
          ExtractApksCommand.builder()
              .setApksArchivePath(apksArchive.getPath())
              .setDeviceSpec(deviceSpec)
              .setOutputDirectory(output);

      return extractApksCommand.build().execute().stream()
          .map(path -> InstallableApk.create(path, apksArchive.getPackageName()))
          .collect(toImmutableList());
    } catch (IncompatibleDeviceException e) {
      logger.warning(
          String.format(
              "Package '%s' is not supported by the attached device (SDK version %d). Skipping.",
              apksArchive.getPackageName(), deviceSpec.getSdkVersion()));
      return ImmutableList.of();
    } catch (IOException e) {
      throw CommandExecutionException.builder()
          .withMessage(
              String.format(
                  "Temp directory to extract files for package '%s' can't be created",
                  apksArchive.getPackageName()))
          .withCause(e)
          .build();
    }
  }

  /**
   * Gets the list of actual .apks files to install, extracting them from the .zip file to a temp
   * directory if necessary.
   */
  private ImmutableList<Path> getActualApksPaths(TempDirectory tempDirectory) throws IOException {
    return getApksArchiveZipPath().isPresent()
        ? extractApksFromZip(getApksArchiveZipPath().get(), tempDirectory)
        : getApksArchivePaths();
  }

  /** Extract the .apks files from a zip file containing multiple .apks files. */
  private static ImmutableList<Path> extractApksFromZip(Path zipPath, TempDirectory tempDirectory)
      throws IOException {
    ImmutableList.Builder<Path> extractedApks = ImmutableList.builder();
    Path zipExtractedSubDirectory = tempDirectory.getPath().resolve("extracted");
    Files.createDirectory(zipExtractedSubDirectory);
    try (ZipFile apksArchiveContainer = new ZipFile(zipPath.toFile())) {
      ImmutableList<ZipEntry> apksToExtractList =
          apksArchiveContainer.stream()
              .filter(
                  zipEntry ->
                      !zipEntry.isDirectory()
                          && zipEntry.getName().toLowerCase(Locale.ROOT).endsWith(".apks"))
              .collect(toImmutableList());
      for (ZipEntry apksToExtract : apksToExtractList) {
        Path extractedApksPath =
            zipExtractedSubDirectory.resolve(
                ZipPath.create(apksToExtract.getName()).getFileName().toString());
        try (InputStream inputStream = BufferedIo.inputStream(apksArchiveContainer, apksToExtract);
            OutputStream outputApks = BufferedIo.outputStream(extractedApksPath)) {
          ByteStreams.copy(inputStream, outputApks);
          extractedApks.add(extractedApksPath);
        }
      }
    }
    return extractedApks.build();
  }

  public static CommandHelp help() {
    return CommandHelp.builder()
        .setCommandName(COMMAND_NAME)
        .setCommandDescription(
            CommandDescription.builder()
                .setShortDescription(
                    "Atomically install APKs and APEXs from multiple APK Sets to a connected"
                        + " device.")
                .addAdditionalParagraph(
                    "This will extract and install from the APK Sets only the APKs that would be"
                        + " served to that device. If the app is not compatible with the device or"
                        + " if the APK Set was generated for a different type of device,"
                        + " this command will fail. If any one of the APK Sets fails to install,"
                        + " none of the APK Sets will be installed.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(ADB_PATH_FLAG.getName())
                .setExampleValue("path/to/adb")
                .setOptional(true)
                .setDescription(
                    "Path to the adb utility. If absent, an attempt will be made to locate it if "
                        + "the %s or %s environment variable is set.",
                    ANDROID_HOME_VARIABLE, SYSTEM_PATH_VARIABLE)
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(DEVICE_ID_FLAG.getName())
                .setExampleValue("device-serial-name")
                .setOptional(true)
                .setDescription(
                    "Device serial name. If absent, this uses the %s environment variable. Either "
                        + "this flag or the environment variable is required when more than one "
                        + "device or emulator is connected.",
                    ANDROID_SERIAL_VARIABLE)
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(APKS_ARCHIVES_FLAG.getName())
                .setExampleValue("/path/to/apks1.apks,/path/to/apks2.apks")
                .setOptional(true)
                .setDescription(
                    "The list of .apks files to install. Either --apks or --apks-zip"
                        + " is required.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(APKS_ARCHIVE_ZIP_FLAG.getName())
                .setExampleValue("/path/to/apks_containing.zip")
                .setOptional(true)
                .setDescription(
                    "Zip file containing .apks files to install. Either --apks or"
                        + " --apks-zip is required.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(ENABLE_ROLLBACK_FLAG.getName())
                .setOptional(true)
                .setDescription(
                    "Enables rollback of the entire atomic install by rolling back any one of the"
                        + " packages.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(UPDATE_ONLY_FLAG.getName())
                .setOptional(true)
                .setDescription(
                    "If set, only packages that are already installed on the device will be"
                        + " updated. Entirely new packages will not be installed.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(NO_COMMIT_FLAG.getName())
                .setOptional(true)
                .setDescription(
                    "Run the full install commands, but abandon the install session instead of"
                        + " committing it.")
                .build())
        .addFlag(
            FlagDescription.builder()
                .setFlagName(AAPT2_PATH_FLAG.getName())
                .setExampleValue("path/to/aapt2")
                .setOptional(true)
                .setDescription("Path to the aapt2 binary to use.")
                .build())
        .build();
  }

  /** Utility for providing an Aapt2Command if it is needed, to be used with Suppliers.memoize. */
  private Aapt2Command getOrExtractAapt2Command(Path tempDirectoryForJarCommand) {
    if (getAapt2Command().isPresent()) {
      return getAapt2Command().get();
    }
    return CommandUtils.extractAapt2FromJar(tempDirectoryForJarCommand);
  }
}
