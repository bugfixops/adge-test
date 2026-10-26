package utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal reader for the resource table (resources.arsc) of an APK: resource id -> type and entry name.
 * FlowDroid 2.12's ARSCFileParser rejects tables written by recent aapt2 versions ("res1 was not zero"), so the
 * table is read here: packages, type/key string pools and type chunks, including sparse, 16-bit offset and
 * compact entries.
 */
public class ArscResources {
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int RES_TABLE_TYPE_TYPE = 0x0201;
    private static final int FLAG_SPARSE = 0x01;
    private static final int FLAG_OFFSET16 = 0x02;
    private static final int ENTRY_FLAG_COMPACT = 0x08;

    /** id -> {package, type, name} */
    public final Map<Integer, String[]> resources = new HashMap<>();
    public final List<String> packages = new ArrayList<>();

    public static ArscResources read(String apkPath) throws IOException {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry("resources.arsc");
            if (entry == null) {
                throw new IOException("no resources.arsc in " + apkPath);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bytes.write(buf, 0, n);
                }
                ArscResources res = new ArscResources();
                res.parse(ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN));
                return res;
            }
        }
    }

    private void parse(ByteBuffer b) {
        if (u16(b, 0) != RES_TABLE_TYPE) {
            throw new IllegalArgumentException("not a resource table");
        }
        int pos = u16(b, 2);
        while (pos + 8 <= b.limit()) {
            int type = u16(b, pos);
            int size = b.getInt(pos + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_TABLE_PACKAGE_TYPE) {
                parsePackage(b, pos);
            }
            pos += size;
        }
    }

    private void parsePackage(ByteBuffer b, int start) {
        int headerSize = u16(b, start + 2);
        int size = b.getInt(start + 4);
        int id = b.getInt(start + 8);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            char c = (char) u16(b, start + 12 + 2 * i);
            if (c == 0) {
                break;
            }
            name.append(c);
        }
        String pkg = name.toString();
        packages.add(pkg);
        int typeStringsOffset = b.getInt(start + 12 + 256);
        int keyStringsOffset = b.getInt(start + 12 + 256 + 8);
        List<String> typeStrings = readStringPool(b, start + typeStringsOffset);
        List<String> keyStrings = readStringPool(b, start + keyStringsOffset);

        int pos = start + headerSize;
        int end = start + size;
        while (pos + 8 <= end) {
            int type = u16(b, pos);
            int chunkSize = b.getInt(pos + 4);
            if (chunkSize <= 0) {
                break;
            }
            if (type == RES_TABLE_TYPE_TYPE) {
                parseType(b, pos, id, pkg, typeStrings, keyStrings);
            }
            pos += chunkSize;
        }
    }

    private void parseType(ByteBuffer b, int start, int packageId, String pkg, List<String> typeStrings, List<String> keyStrings) {
        int headerSize = u16(b, start + 2);
        int typeId = b.get(start + 8) & 0xff;
        int flags = b.get(start + 9) & 0xff;
        int entryCount = b.getInt(start + 12);
        int entriesStart = b.getInt(start + 16);
        String typeName = typeId - 1 < typeStrings.size() ? typeStrings.get(typeId - 1) : ("type" + typeId);
        int offsets = start + headerSize;
        for (int i = 0; i < entryCount; i++) {
            int index;
            long offset;
            if ((flags & FLAG_SPARSE) != 0) {
                index = u16(b, offsets + 4 * i);
                offset = u16(b, offsets + 4 * i + 2) * 4L;
            } else if ((flags & FLAG_OFFSET16) != 0) {
                index = i;
                int o = u16(b, offsets + 2 * i);
                if (o == 0xffff) {
                    continue;
                }
                offset = o * 4L;
            } else {
                index = i;
                long o = b.getInt(offsets + 4 * i) & 0xffffffffL;
                if (o == 0xffffffffL) {
                    continue;
                }
                offset = o;
            }
            int e = (int) (start + entriesStart + offset);
            if (e + 8 > b.limit()) {
                continue;
            }
            int entryFlags = u16(b, e + 2);
            int key = (entryFlags & ENTRY_FLAG_COMPACT) != 0 ? u16(b, e) : b.getInt(e + 4);
            if (key < 0 || key >= keyStrings.size()) {
                continue;
            }
            int resId = (packageId << 24) | (typeId << 16) | index;
            resources.putIfAbsent(resId, new String[]{pkg, typeName, keyStrings.get(key)});
        }
    }

    private static List<String> readStringPool(ByteBuffer b, int start) {
        List<String> out = new ArrayList<>();
        if (u16(b, start) != RES_STRING_POOL_TYPE) {
            return out;
        }
        int headerSize = u16(b, start + 2);
        int count = b.getInt(start + 8);
        int flags = b.getInt(start + 16);
        int stringsStart = b.getInt(start + 20);
        boolean utf8 = (flags & 0x100) != 0;
        for (int i = 0; i < count; i++) {
            int p = start + stringsStart + b.getInt(start + headerSize + 4 * i);
            if (utf8) {
                // UTF-16 length, then UTF-8 byte length, each 1 or 2 bytes
                p += (b.get(p) & 0x80) != 0 ? 2 : 1;
                int len = b.get(p) & 0xff;
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7f) << 8) | (b.get(p + 1) & 0xff);
                    p += 2;
                } else {
                    p += 1;
                }
                byte[] s = new byte[len];
                for (int k = 0; k < len; k++) {
                    s[k] = b.get(p + k);
                }
                out.add(new String(s, StandardCharsets.UTF_8));
            } else {
                int len = u16(b, p);
                if ((len & 0x8000) != 0) {
                    len = ((len & 0x7fff) << 16) | u16(b, p + 2);
                    p += 4;
                } else {
                    p += 2;
                }
                char[] s = new char[len];
                for (int k = 0; k < len; k++) {
                    s[k] = (char) u16(b, p + 2 * k);
                }
                out.add(new String(s));
            }
        }
        return out;
    }

    private static int u16(ByteBuffer b, int pos) {
        return b.getShort(pos) & 0xffff;
    }
}
