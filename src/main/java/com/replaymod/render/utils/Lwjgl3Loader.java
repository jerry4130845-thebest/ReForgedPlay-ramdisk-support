package com.replaymod.render.utils;

import com.replaymod.core.ReplayMod;
import com.replaymod.render.rendering.Frame;
import com.replaymod.render.rendering.FrameConsumer;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;



public class Lwjgl3Loader extends URLClassLoader {
    static { registerAsParallelCapable(); }
    private static Path tempJarFile;
    private static Lwjgl3Loader instance;

    private final Set<String> implClasses = new CopyOnWriteArraySet<>();
    private final Path ownedJar;
    private boolean tempDeleted;

    private Lwjgl3Loader(Path jarFile) throws IOException, ReflectiveOperationException {
        super(new URL[] { jarFile.toUri().toURL() }, Lwjgl3Loader.class.getClassLoader());
        this.ownedJar = jarFile;
        try {
            // Natives remain in the configured cache, not in the temporary-JAR directory.
            Path nativesDir = ReplayMod.instance.folders.getCacheFolder().resolve("lwjgl-natives");
            Class<?> configClass = Class.forName("org.lwjgl.system.Configuration", true, this);
            Method setMethod = configClass.getMethod("set", Object.class);
            setMethod.invoke(
                    configClass.getField("SHARED_LIBRARY_EXTRACT_DIRECTORY").get(null),
                    nativesDir.toAbsolutePath().toString()
            );
            setMethod.invoke(
                    configClass.getField("SHARED_LIBRARY_EXTRACT_PATH").get(null),
                    (Object) null
            );
        } catch (IOException | ReflectiveOperationException | RuntimeException | Error failure) {
            try { close(); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private boolean canBeSharedWithMc(String name) {
        if (name.startsWith("org.lwjgl.")) {
            return false; // MC may have a different version
        }
        for (String implClass : implClasses) {
            if (name.startsWith(implClass)) {
                return false; // depends on above lwjgl
            }
        }
        return true;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!canBeSharedWithMc(name)) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> cls = findLoadedClass(name);
                if (cls == null) {
                    cls = findClass(name);
                }
                if (resolve) {
                    resolveClass(cls);
                }
                return cls;
            }
        } else {
            return super.loadClass(name, resolve);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            String path = name.replace('.', '/').concat(".class");
            URL url = getParent().getResource(path);
            if (url == null) {
                throw e;
            }
            try {
                byte[] bytes = IOUtils.toByteArray(url);
                return defineClass(name, bytes, 0, bytes.length, (ProtectionDomain) null);
            } catch (IOException e1) {
                throw new ClassNotFoundException(name, e1);
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (Lwjgl3Loader.class) {
            super.close(); // A failed close must not license deleting a JAR still in use.
            if (instance == this) instance = null;
            // A cleanup retry on this old loader must never remove a newer loader's JAR.
            if (ownedJar.equals(tempJarFile)) tempJarFile = null;
            if (!tempDeleted) {
                ReplayMod.instance.files.deleteOwnedTemp(ownedJar);
                tempDeleted = true;
            }
        }
    }

    private static synchronized Path getJarFile() throws IOException {
        if (tempJarFile == null) {
            Path jarFile = ReplayMod.instance.files.createTempFile("replaymod-lwjgl", ".jar");
            try (InputStream in = Lwjgl3Loader.class.getResourceAsStream("lwjgl.jar")) {
                if (in == null) {
                    throw new IOException("Failed to find embedded lwjgl.jar file.");
                }
                // The service has already exclusively created this file; do not replace its identity.
                try (java.io.OutputStream out = Files.newOutputStream(jarFile)) {
                    IOUtils.copy(in, out);
                }
            } catch (IOException | RuntimeException | Error failure) {
                try { ReplayMod.instance.files.deleteOwnedTemp(jarFile); }
                catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            // Keep the JAR while the classloader can still load classes/resources from it.
            tempJarFile = jarFile;
        }
        return tempJarFile;
    }

    public static synchronized Lwjgl3Loader instance() {
        if (instance == null) {
            try {
                instance = new Lwjgl3Loader(getJarFile());
            } catch (IOException | ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    public static <P extends Frame> FrameConsumer<P> createFrameConsumer(
            Class<? extends FrameConsumer<P>> implClass,
            Class<?>[] parameterTypes,
            Object[] args
    ) {
        try {
            Lwjgl3Loader loader = instance();
            loader.implClasses.add(implClass.getName());
            Class<?> realClass = Class.forName(implClass.getName(), true, loader);
            Constructor<?> constructor = realClass.getConstructor(parameterTypes);
            return (FrameConsumer<P>) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
