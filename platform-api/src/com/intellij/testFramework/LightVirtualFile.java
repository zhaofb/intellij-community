package com.intellij.testFramework;

import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.vfs.*;
import com.intellij.util.LocalTimeCounter;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.Charset;

public class LightVirtualFile extends DeprecatedVirtualFile {
  private FileType myFileType;
  private CharSequence myContent = "";
  private String myName = "";
  private long myModStamp = LocalTimeCounter.currentTime();
  private boolean myIsWritable = true;
  private Language myLanguage;
  private VirtualFileListener myListener = null;

  public LightVirtualFile() {
    this("");
  }

  public LightVirtualFile(@NonNls String name) {
    this(name, "");
  }

  public LightVirtualFile(@NonNls String name, CharSequence content) {
    this(name, null, content, LocalTimeCounter.currentTime());
  }

  public LightVirtualFile(final String name, final FileType fileType, final CharSequence text) {
    this(name, fileType, text, LocalTimeCounter.currentTime());
  }

  public LightVirtualFile(VirtualFile original, final CharSequence text, long modificationStamp) {
    this(original.getName(), original.getFileType(), text, modificationStamp);
    setCharset(original.getCharset());
  }

  public LightVirtualFile(final String name, final FileType fileType, final CharSequence text, final long modificationStamp) {
    myName = name;
    myFileType = fileType;
    myContent = text;
    myModStamp = modificationStamp;
    if (fileType instanceof LanguageFileType) {
      setCharset(((LanguageFileType)fileType).extractCharsetFromFileContent(null, this, text.toString()));
    }
  }

  public LightVirtualFile(final String name, final Language language, final CharSequence text) {
    myName = name;
    myContent = text;
    myModStamp = LocalTimeCounter.currentTime();
    setLanguage(language);
  }

  public Language getLanguage() {
    return myLanguage;
  }

  public void setLanguage(final Language language) {
    myLanguage = language;
    myFileType = language.getAssociatedFileType();
    if (myFileType == null) myFileType = FileTypeManager.getInstance().getFileTypeByFileName(myName);
  }

  public void setFileType(final FileType fileType) {
    myFileType = fileType;
  }

  private static class MyVirtualFileSystem extends DeprecatedVirtualFileSystem {
    @NonNls private static final String PROTOCOL = "mock";

    public String getProtocol() {
      return PROTOCOL;
    }

    @Nullable
    public VirtualFile findFileByPath(@NotNull String path) {
      return null;
    }

    public void refresh(boolean asynchronous) {
    }

    @Nullable
    public VirtualFile refreshAndFindFileByPath(String path) {
      return null;
    }

    public void deleteFile(Object requestor, VirtualFile vFile) throws IOException {
    }

    public void moveFile(Object requestor, VirtualFile vFile, VirtualFile newParent) throws IOException {
    }

    public VirtualFile copyFile(Object requestor, VirtualFile vFile, VirtualFile newParent, final String copyName) throws IOException {
      throw new IOException("Cannot copy files");
    }

    public void renameFile(Object requestor, VirtualFile vFile, String newName) throws IOException {
    }

    public VirtualFile createChildFile(Object requestor, VirtualFile vDir, String fileName) throws IOException {
      throw new IOException("Cannot create files");
    }

    public VirtualFile createChildDirectory(Object requestor, VirtualFile vDir, String dirName) throws IOException {
      throw new IOException("Cannot create directories");
    }
  }

  private static final MyVirtualFileSystem ourFileSystem = new MyVirtualFileSystem();

  @NotNull
  public VirtualFileSystem getFileSystem() {
    return ourFileSystem;
  }

  @NotNull
  public FileType getFileType() {
    return myFileType != null ? myFileType : super.getFileType();
  }

  public String getPath() {
    return "/" + getName();
  }

  @NotNull
  public String getName() {
    return myName;
  }

  public boolean isWritable() {
    return myIsWritable;
  }

  public boolean isDirectory() {
    return false;
  }

  public boolean isValid() {
    return true;
  }

  public VirtualFile getParent() {
    return null;
  }

  public VirtualFile[] getChildren() {
    return EMPTY_ARRAY;
  }

  public InputStream getInputStream() throws IOException {
    return new ByteArrayInputStream(contentsToByteArray());
  }

  public OutputStream getOutputStream(Object requestor, final long newModificationStamp, long newTimeStamp) throws IOException {
    return new ByteArrayOutputStream() {
      public void close() {
        myModStamp = newModificationStamp;
        myContent = toString();
      }
    };
  }

  public byte[] contentsToByteArray() throws IOException {
    final Charset charset = getCharset();
    final String s = getContent().toString();
    return charset != null ? s.getBytes(charset.name()) : s.getBytes();
  }

  public long getModificationStamp() {
    return myModStamp;
  }

  public long getTimeStamp() {
    return 0; // todo[max] : Add UnsupporedOperationException at better times.
  }

  public long getLength() {
    try {
      return contentsToByteArray().length;
    }
    catch (IOException e) {
      e.printStackTrace();
      assert false;
      return 0;
    }
  }

  public void refresh(boolean asynchronous, boolean recursive, Runnable postRunnable) {
  }

  public void setContent(Object requestor, CharSequence content, boolean fireEvent) {
    myContent = content;
    if (fireEvent) {
      long oldStamp = myModStamp;
      myModStamp = LocalTimeCounter.currentTime();
      myListener.contentsChanged(new VirtualFileEvent(requestor, this, null, oldStamp, myModStamp));
    }
  }

  public void setWritable(boolean b) {
    myIsWritable = b;
  }

  public void rename(Object requestor, @NotNull String newName) throws IOException {
    myName = newName;
  }

  public CharSequence getContent() {
    return myContent;
  }
}
