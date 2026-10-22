package com.cheatprogram.core;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Recycles exactly the mod-owned directory; there is no recursive-delete or permanent-delete fallback. */
final class WindowsRecycleBin {
    private static final Guid.GUID FILE_OPERATION = new Guid.GUID("{3AD05575-8857-4850-9277-11B85BDB8E09}");
    private static final Guid.GUID IID_FILE_OPERATION = new Guid.GUID("{947AAB5F-0A5C-4C13-B4D6-4BF7836FC9F8}");
    private static final Guid.IID IID_SHELL_ITEM = new Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");
    private static final int REPARSE_POINT = 0x400;
    // Always recycle, register undo, fail on the first error; never silently continue or permanently delete.
    private static final int FLAGS = 0x00080000 | 0x20000000 | 0x00100000 | 0x0400 | 0x0010 | 0x0004;
    private interface ShellItems extends StdCallLibrary {
        ShellItems INSTANCE = Native.load("shell32", ShellItems.class, W32APIOptions.UNICODE_OPTIONS);
        HRESULT SHCreateItemFromParsingName(WString path, Pointer context, Guid.REFIID iid, PointerByReference item);
    }
    private static final class FileOperation extends Unknown {
        FileOperation(Pointer pointer) { super(pointer); }
        HRESULT call(int index, Object... arguments) {
            Object[] parameters = new Object[arguments.length + 1]; parameters[0] = getPointer();
            System.arraycopy(arguments, 0, parameters, 1, arguments.length);
            return (HRESULT) _invokeNativeObject(index, parameters, HRESULT.class);
        }
    }
    private WindowsRecycleBin() { }

    static Path validatedDirectory() throws IOException {
        if (!Platform.isWindows()) throw new IOException("ui.reset.windowsOnly");
        Path config = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
        Path expected = config.resolve("cheatprogram-open").normalize(), selected = Config.directory().toAbsolutePath().normalize();
        if (!expected.equals(selected) || !selected.getParent().equals(config)
                || !selected.getFileName().toString().equals("cheatprogram") || selected.getRoot() == null
                || !selected.getRoot().toString().matches("[A-Za-z]:\\\\")) throw new IOException("ui.reset.invalidPath");
        for (Path part = selected; part != null; part = part.getParent()) rejectReparsePoint(part);
        if (Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS) || !selected.toRealPath().equals(selected))
                throw new IOException("ui.reset.invalidPath");
            Files.walkFileTree(selected, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    rejectReparsePoint(directory);
                    if (!directory.toAbsolutePath().normalize().startsWith(selected)) throw new IOException("ui.reset.invalidPath");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    rejectReparsePoint(file); return FileVisitResult.CONTINUE;
                }
            });
        }
        return selected;
    }
    private static void rejectReparsePoint(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        int attributes = Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if (attributes == -1 || (attributes & REPARSE_POINT) != 0 || Files.isSymbolicLink(path))
            throw new IOException("ui.reset.invalidPath");
    }
    static void recycleValidatedDirectory(Path expected) throws IOException {
        Path selected = validatedDirectory();
        if (!expected.equals(selected)) throw new IOException("ui.reset.invalidPath");
        if (!Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) return;
        HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, 2);
        check(initialized); FileOperation operation = null; Unknown shellItem = null;
        try {
            PointerByReference pointer = new PointerByReference();
            check(Ole32.INSTANCE.CoCreateInstance(FILE_OPERATION, Pointer.NULL, 1, IID_FILE_OPERATION, pointer));
            operation = new FileOperation(pointer.getValue());
            check(operation.call(5, FLAGS));
            PointerByReference item = new PointerByReference();
            check(ShellItems.INSTANCE.SHCreateItemFromParsingName(new WString(selected.toString()), Pointer.NULL,
                    new Guid.REFIID(IID_SHELL_ITEM), item));
            shellItem = new Unknown(item.getValue());
            check(operation.call(18, item.getValue(), Pointer.NULL));
            check(operation.call(21));
            IntByReference aborted = new IntByReference(); check(operation.call(22, aborted));
            if (aborted.getValue() != 0 || Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ui.reset.recycleFailed");
        } finally {
            if (shellItem != null) shellItem.Release();
            if (operation != null) operation.Release();
            Ole32.INSTANCE.CoUninitialize();
        }
    }
    private static void check(HRESULT result) throws IOException {
        if (result.intValue() < 0) throw new IOException("ui.reset.recycleFailed");
    }
}
