/**
 * Copyright 2016 Otto (GmbH & Co KG)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.schedoscope.export.testsupport;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Random;

public class TestUtils {
    private static final Random RANDOM = new Random();

    private TestUtils() {
    }

    public static File constructTempDir(String dirPrefix) {
        File file = new File(System.getProperty("java.io.tmpdir"), dirPrefix
                + RANDOM.nextInt(10000000));
        if (!file.mkdirs()) {
            throw new RuntimeException("could not create temp directory: "
                    + file.getAbsolutePath());
        }
        file.deleteOnExit();
        return file;
    }

    public static int getAvailablePort() {
        try {
            ServerSocket socket = new ServerSocket(0);
            try {
                return socket.getLocalPort();
            } finally {
                socket.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot find available port: "
                    + e.getMessage(), e);
        }
    }

    public static boolean deleteFile(File path) throws FileNotFoundException {
        if (!path.exists()) {
            throw new FileNotFoundException(path.getAbsolutePath());
        }
        boolean ret = true;
        if (path.isDirectory()) {
            File[] files = path.listFiles();
            if (files != null) {
                for (File f : files) {
                    ret = ret && deleteFile(f);
                }
            }
        }
        return ret && path.delete();
    }
}
