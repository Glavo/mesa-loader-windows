/*
 * Copyright 2024 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.mesa;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.Locale;
import java.util.Properties;

public final class Loader {
    public static void premain(String name) {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            System.err.println("[mesa-loader] unsupported operating system: " + System.getProperty("os.name"));
            return;
        }

        String arch;
        switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "x8664":
            case "x86-64":
            case "x86_64":
            case "amd64":
            case "ia32e":
            case "em64t":
            case "x64":
                arch = "x64";
                break;
            case "x8632":
            case "x86-32":
            case "x86_32":
            case "x86":
            case "i86pc":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
            case "ia32":
            case "x32":
                arch = "x86";
                break;
            default:
                System.err.println("[mesa-loader] Unsupported architecture: " + System.getProperty("os.arch"));
                return;
        }

        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Loader.class.getResourceAsStream("version.properties"), "UTF-8")) {
            properties.load(reader);
        } catch (Throwable ignored) {
        }

        String mesaVersion = properties.getProperty("mesa.version");
        if (mesaVersion == null) {
            System.err.println("[mesa-loader] Missing mesa version property in version.properties");
            return;
        }

        System.out.println("[mesa-loader] Mesa Version: " + mesaVersion);
        System.out.println("[mesa-loader] Loader Version: " + properties.getProperty("loader.version"));

        String[] files;

        if (name == null || name.isEmpty())
            name = "llvmpipe";

        switch (name.toLowerCase(Locale.ROOT)) {
            case "zink":
                files = new String[]{"libglapi.dll", "libgallium_wgl.dll", "opengl32.dll"};
                break;
            case "llvmpipe":
            case "d3d12":
                files = new String[]{"libglapi.dll", "libgallium_wgl.dll", "opengl32.dll", "dxil.dll"};
                break;
            default:
                System.err.println("[mesa-loader] unknown name: " + name);
                return;
        }

        File targetDir = new File(String.format("%s/glavo-mesa-loader-%s-%s",
                System.getProperty("java.io.tmpdir"),
                mesaVersion,
                arch)).getAbsoluteFile();
        //noinspection ResultOfMethodCallIgnored
        targetDir.mkdirs();

        File lockFile = new File(targetDir, "lock");
        FileLock lock = null;
        try (FileOutputStream lockFileStream = new FileOutputStream(lockFile)) {
            for (int retry = 0; retry < 5; retry++) {
                lock = lockFileStream.getChannel().tryLock();
                if (lock != null) {
                    break;
                }
                try {
                    Thread.sleep(5 * 1000);
                } catch (InterruptedException e) {
                    System.out.println("[mesa-loader] Interrupted while waiting for lock");
                    return;
                }
            }

            if (lock == null) {
                System.err.println("[mesa-loader] Could not get file lock");
                return;
            }

            byte[] buffer = new byte[8192];
            for (String file : files) {
                try (InputStream input = Loader.class.getResourceAsStream(arch + "/" + file)) {
                    if (input == null) {
                        System.err.println("[mesa-loader] " + file + " not exists");
                        return;
                    }

                    File targetFile = new File(targetDir, file);

                    if (!targetFile.exists() || targetFile.length() != input.available()) {
                        System.out.println("[mesa-loader] Extract " + file + " to " + targetDir);
                        try (FileOutputStream out = new FileOutputStream(targetFile)) {
                            int n;
                            while ((n = input.read(buffer)) > 0) {
                                out.write(buffer, 0, n);
                            }
                        }
                    }

                    String dllPath = targetFile.getAbsolutePath();
                    System.out.println("[mesa-loader] Loading " + dllPath);
                    System.load(dllPath);
                } catch (IOException e) {
                    System.err.println("[mesa-loader] Failed to extract " + file);
                    e.printStackTrace(System.err);
                } catch (UnsatisfiedLinkError e) {
                    System.err.println("[mesa-loader] Failed to load " + file);
                    e.printStackTrace(System.err);
                }
            }
        } catch (IOException e) {
            System.err.println("[mesa-loader] Failed to get file lock");
            e.printStackTrace(System.err);
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (Throwable e) {
                    System.err.println("[mesa-loader] An exception occurred while releasing the file lock");
                    e.printStackTrace(System.err);
                }
            }
        }
    }
}

