package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

/**
 * android.net.Uri для тестов на JVM: в заглушке android.jar Uri.parse и
 * Uri.EMPTY дают null, а конструктор Uri виден только из пакета android.net.
 * Адрес без схемы и пути: DefaultExtractorsFactory видит «тип файла
 * неизвестен» и даёт обычный порядок экстракторов (как у content:// без
 * расширения), BundledExtractorsAdapter — непустой адрес для текста ошибки.
 */
public final class TestUri extends Uri {
  private final String text;

  public TestUri(String text) {
    this.text = text;
  }

  @Override public boolean isHierarchical() { return false; }
  @Override public boolean isRelative() { return false; }
  @Override public String getScheme() { return null; }
  @Override public String getSchemeSpecificPart() { return null; }
  @Override public String getEncodedSchemeSpecificPart() { return null; }
  @Override public String getAuthority() { return null; }
  @Override public String getEncodedAuthority() { return null; }
  @Override public String getUserInfo() { return null; }
  @Override public String getEncodedUserInfo() { return null; }
  @Override public String getHost() { return null; }
  @Override public int getPort() { return -1; }
  @Override public String getPath() { return null; }
  @Override public String getEncodedPath() { return null; }
  @Override public String getQuery() { return null; }
  @Override public String getEncodedQuery() { return null; }
  @Override public String getFragment() { return null; }
  @Override public String getEncodedFragment() { return null; }
  @Override public List<String> getPathSegments() { return Collections.emptyList(); }
  @Override public String getLastPathSegment() { return null; }
  @Override public String toString() { return text; }
  @Override public Builder buildUpon() { return null; }
  @Override public int describeContents() { return 0; }
  @Override public void writeToParcel(Parcel dest, int flags) {}
}
