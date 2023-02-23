package org.glavo.mesa;

import java.io.*;
import java.util.Locale;
import java.util.Properties;

public final class Loader {
    public static void premain(String name) {
        if (!System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("win")) {
            System.err.println("[mesa-loader] unsupported operating system: " + System.getProperty("os.name"));
            return;
        }

        String arch;
        switch (System.getProperty("os.arch").toLowerCase(Locale.ENGLISH)) {
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
                System.err.println("[mesa-loader] unsupported architecture: " + System.getProperty("os.arch"));
                return;
        }

        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Loader.class.getResourceAsStream("version.properties"), "UTF-8")) {
            properties.load(reader);
        } catch (Throwable ignored) {
        }

        String mesaVersion = properties.getProperty("mesa.version");
        if (mesaVersion != null)
            System.out.println("[mesa-loader] Mesa Version: " + mesaVersion);

        String loaderVersion = properties.getProperty("loader.version");
        boolean temp = loaderVersion == null || loaderVersion.endsWith("SNAPSHOT");

        String[] files;

        if (name == null || name.isEmpty())
            name = "llvmpipe";

        switch (name.toLowerCase(Locale.ENGLISH)) {
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
                arch, loaderVersion == null ? System.nanoTime() : loaderVersion)).getAbsoluteFile();
        targetDir.mkdirs();
        if (temp) {
            targetDir.deleteOnExit();
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
                    if (temp) {
                        targetFile.deleteOnExit();
                    }

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
                e.printStackTrace();
            } catch (UnsatisfiedLinkError e) {
                System.err.println("[mesa-loader] Failed to load " + file);
                e.printStackTrace();
            }
        }
    }
}

