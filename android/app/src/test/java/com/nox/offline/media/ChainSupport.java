package com.nox.offline.media;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.DataReader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.BundledExtractorsAdapter;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Мост для тестов на JVM. android.net.Uri здесь не создать (заглушка
 * android.jar), а BundledExtractorsAdapter передаёт адрес только фабрике и в
 * текст ошибки — поэтому адрес null, а фабрика его не читает. Замеры памяти —
 * через java.lang.management (в android.jar его нет, а в JVM тестов есть).
 */
@OptIn(markerClass = UnstableApi.class)
final class ChainSupport {
  private ChainSupport() {}

  static ExtractorsFactory factory(Supplier<Extractor[]> make) {
    return new ExtractorsFactory() {
      @Override
      public Extractor[] createExtractors() {
        return make.get();
      }

      @Override
      public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return make.get();
      }
    };
  }

  static void init(BundledExtractorsAdapter adapter, DataReader reader, long position, long length, ExtractorOutput output)
      throws IOException {
    adapter.init(reader, dummyUri(), Collections.emptyMap(), position, length, output);
  }

  /** Адрес без расширения ([android.net.TestUri]): тип файла неизвестен — обычный порядок экстракторов. */
  static Uri dummyUri() {
    return new android.net.TestUri("content://nox.test/marathon");
  }

  /** Список DefaultExtractorsFactory для адреса без расширения. */
  static Extractor[] defaultExtractors() {
    return new androidx.media3.extractor.DefaultExtractorsFactory().createExtractors(dummyUri(), Collections.emptyMap());
  }

  // java.lang.management в android.jar нет: компиляция идёт против него, а
  // выполнение — на обычной JVM, где он есть. Поэтому — через отражение.

  private static List<?> heapPools() throws ReflectiveOperationException {
    Class<?> mf = Class.forName("java.lang.management.ManagementFactory");
    Class<?> pool = Class.forName("java.lang.management.MemoryPoolMXBean");
    Object heap = Class.forName("java.lang.management.MemoryType").getField("HEAP").get(null);
    List<Object> out = new java.util.ArrayList<>();
    for (Object p : (List<?>) mf.getMethod("getMemoryPoolMXBeans").invoke(null)) {
      if (pool.getMethod("getType").invoke(p) == heap) out.add(p);
    }
    return out;
  }

  private static long sum(String method) {
    try {
      Class<?> pool = Class.forName("java.lang.management.MemoryPoolMXBean");
      Class<?> usage = Class.forName("java.lang.management.MemoryUsage");
      long sum = 0;
      for (Object p : heapPools()) sum += (Long) usage.getMethod("getUsed").invoke(pool.getMethod(method).invoke(p));
      return sum;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  static void resetPeak() {
    try {
      Class<?> pool = Class.forName("java.lang.management.MemoryPoolMXBean");
      for (Object p : heapPools()) pool.getMethod("resetPeakUsage").invoke(p);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Сумма пиков пулов кучи с последнего сброса (вместе с ещё не собранным мусором). */
  static long peak() {
    return sum("getPeakUsage");
  }

  static long used() {
    return sum("getUsage");
  }

  /** Сколько байт выделил текущий поток за всё время (com.sun.management.ThreadMXBean). */
  static long allocated() {
    try {
      Object t = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
      Class<?> sun = Class.forName("com.sun.management.ThreadMXBean");
      return (Long) sun.getMethod("getThreadAllocatedBytes", long.class).invoke(t, Thread.currentThread().getId());
    } catch (ReflectiveOperationException e) {
      return -1;
    }
  }
}
