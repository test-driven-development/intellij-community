package com.intellij.openapi.editor.impl.injected;

import com.intellij.ide.CopyProvider;
import com.intellij.ide.CutProvider;
import com.intellij.ide.DeleteProvider;
import com.intellij.ide.PasteProvider;
import com.intellij.ide.highlighter.HighlighterFactory;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorGutterComponentEx;
import com.intellij.openapi.editor.ex.FocusChangeListener;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.util.containers.WeakList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.util.Iterator;

/**
 * @author Alexey
 */
public class EditorWindow implements EditorEx, UserDataHolderEx {
  private final DocumentWindow myDocumentWindow;
  private final EditorImpl myDelegate;
  private volatile PsiFile myInjectedFile;
  private final boolean myOneLine;
  private final CaretModelWindow myCaretModelDelegate;
  private final SelectionModelWindow mySelectionModelDelegate;
  private static final WeakList<EditorWindow> allEditors = new WeakList<EditorWindow>();
  private boolean myDisposed;
  private final MarkupModelWindow myMarkupModelDelegate;

  public static Editor create(@NotNull final DocumentWindow documentRange, @NotNull final EditorImpl editor, @NotNull final PsiFile injectedFile) {
    for (EditorWindow editorWindow : allEditors) {
      if (editorWindow.getDocument() == documentRange && editorWindow.getDelegate() == editor) {
        editorWindow.myInjectedFile = injectedFile;
        if (editorWindow.isValid()) {
          return editorWindow;
        }
      }
    }
    return new EditorWindow(documentRange, editor, injectedFile, documentRange.isOneLine());
  }

  private EditorWindow(@NotNull DocumentWindow documentWindow, @NotNull final EditorImpl delegate, @NotNull PsiFile injectedFile, boolean oneLine) {
    myDocumentWindow = documentWindow;
    myDelegate = delegate;
    myInjectedFile = injectedFile;
    myOneLine = oneLine;
    myCaretModelDelegate = new CaretModelWindow(myDelegate.getCaretModel(), this);
    mySelectionModelDelegate = new SelectionModelWindow(myDelegate, myDocumentWindow,this);
    myMarkupModelDelegate = new MarkupModelWindow((MarkupModelEx)myDelegate.getMarkupModel(), myDocumentWindow);

    disposeInvalidEditors();
    allEditors.add(this);
  }

  private void disposeInvalidEditors() {
    Iterator<EditorWindow> iterator = allEditors.iterator();
    while (iterator.hasNext()) {
      EditorWindow editorWindow = iterator.next();
      if (!editorWindow.isValid() || myDocumentWindow.intersects(editorWindow.myDocumentWindow)) {
        editorWindow.disposeModel();

        InjectedLanguageUtil.clearCaches(editorWindow.getInjectedFile());
        iterator.remove();
      }
    }
  }

  private boolean isValid() {
    return !isDisposed() && myInjectedFile.isValid() && myDocumentWindow.isValid();
  }

  public PsiFile getInjectedFile() {
    return myInjectedFile;
  }
  public LogicalPosition hostToInjected(LogicalPosition pos) {
    assert isValid();
    int offsetInInjected = myDocumentWindow.hostToInjected(myDelegate.logicalPositionToOffset(pos));
    return offsetToLogicalPosition(offsetInInjected);
  }

  public LogicalPosition injectedToHost(LogicalPosition pos) {
    assert isValid();
    int offsetInHost = myDocumentWindow.injectedToHost(logicalPositionToOffset(pos));
    return myDelegate.offsetToLogicalPosition(offsetInHost);
  }

  private void disposeModel() {
    assert !myDisposed;
    myCaretModelDelegate.disposeModel();

    for (EditorMouseListener wrapper : myEditorMouseListeners.wrappers()) {
      myDelegate.removeEditorMouseListener(wrapper);
    }
    myEditorMouseListeners.clear();
    for (EditorMouseMotionListener wrapper : myEditorMouseMotionListeners.wrappers()) {
      myDelegate.removeEditorMouseMotionListener(wrapper);
    }
    myEditorMouseMotionListeners.clear();
    myDisposed = true;
  }

  public boolean isViewer() {
    return myDelegate.isViewer();
  }

  public boolean isRendererMode() {
    return myDelegate.isRendererMode();
  }

  public void setRendererMode(final boolean isRendererMode) {
    myDelegate.setRendererMode(isRendererMode);
  }

  public void setFile(final VirtualFile vFile) {
    myDelegate.setFile(vFile);
  }

  public void setHeaderComponent(@Nullable JComponent header) {

  }

  public boolean hasHeaderComponent() {
    return false;
  }

  @Nullable
  public JComponent getHeaderComponent() {
    return null;
  }

  @NotNull
  public SelectionModel getSelectionModel() {
    return mySelectionModelDelegate;
  }

  @NotNull
  public MarkupModel getMarkupModel() {
    return myMarkupModelDelegate;
  }

  @NotNull
  public FoldingModel getFoldingModel() {
    return myDelegate.getFoldingModel();
  }

  @NotNull
  public CaretModel getCaretModel() {
    return myCaretModelDelegate;
  }

  @NotNull
  public ScrollingModel getScrollingModel() {
    return myDelegate.getScrollingModel();
  }

  @NotNull
  public EditorSettings getSettings() {
    return myDelegate.getSettings();
  }

  public void reinitSettings() {
    myDelegate.reinitSettings();
  }

  public void setFontSize(final int fontSize) {
    myDelegate.setFontSize(fontSize);
  }

  public void setHighlighter(final EditorHighlighter highlighter) {
    myDelegate.setHighlighter(highlighter);
  }

  public EditorHighlighter getHighlighter() {
    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
    EditorHighlighter highlighter = HighlighterFactory.createHighlighter(myInjectedFile.getFileType(), scheme, getProject());
    highlighter.setText(getDocument().getText());
    return highlighter;
  }

  @NotNull
  public JComponent getContentComponent() {
    return myDelegate.getContentComponent();
  }

  public EditorGutterComponentEx getGutterComponentEx() {
    return myDelegate.getGutterComponentEx();
  }

  public void addPropertyChangeListener(final PropertyChangeListener listener) {
    myDelegate.addPropertyChangeListener(listener);
  }

  public void removePropertyChangeListener(final PropertyChangeListener listener) {
    myDelegate.removePropertyChangeListener(listener);
  }

  public void setInsertMode(final boolean mode) {
    myDelegate.setInsertMode(mode);
  }

  public boolean isInsertMode() {
    return myDelegate.isInsertMode();
  }

  public void setColumnMode(final boolean mode) {
    myDelegate.setColumnMode(mode);
  }

  public boolean isColumnMode() {
    return myDelegate.isColumnMode();
  }

  @NotNull
  public VisualPosition xyToVisualPosition(@NotNull final Point p) {
    return logicalToVisualPosition(xyToLogicalPosition(p));
  }

  @NotNull
  public VisualPosition offsetToVisualPosition(final int offset) {
    return logicalToVisualPosition(offsetToLogicalPosition(offset));
  }

  @NotNull
  public LogicalPosition offsetToLogicalPosition(final int offset) {
    assert isValid();
    int lineNumber = myDocumentWindow.getLineNumber(offset);
    int lineStartOffset = myDocumentWindow.getLineStartOffset(lineNumber);
    int column = calcColumnNumber(offset-lineStartOffset, lineNumber);
    return new LogicalPosition(lineNumber, column);
  }

  @NotNull
  public LogicalPosition xyToLogicalPosition(@NotNull final Point p) {
    assert isValid();
    LogicalPosition hostPos = myDelegate.xyToLogicalPosition(p);
    return hostToInjected(hostPos);
  }

  private LogicalPosition fitInsideEditor(LogicalPosition pos) {
    int lineCount = myDocumentWindow.getLineCount();
    if (pos.line >= lineCount) {
      pos = new LogicalPosition(lineCount-1, pos.column);
    }
    int lineLength = myDocumentWindow.getLineEndOffset(pos.line) - myDocumentWindow.getLineStartOffset(pos.line);
    if (pos.column >= lineLength) {
      pos = new LogicalPosition(pos.line, lineLength-1);
    }
    return pos;
  }

  @NotNull
  public Point logicalPositionToXY(@NotNull final LogicalPosition pos) {
    assert isValid();
    return myDelegate.logicalPositionToXY(injectedToHost(fitInsideEditor(pos)));
  }

  @NotNull
  public Point visualPositionToXY(@NotNull final VisualPosition pos) {
    assert isValid();
    return logicalPositionToXY(visualToLogicalPosition(pos));
  }

  public void repaint(final int startOffset, final int endOffset) {
    assert isValid();
    myDelegate.repaint(myDocumentWindow.injectedToHost(startOffset), myDocumentWindow.injectedToHost(endOffset));
  }

  @NotNull
  public DocumentWindow getDocument() {
    return myDocumentWindow;
  }

  @NotNull
  public JComponent getComponent() {
    return myDelegate.getComponent();
  }

  private final ListenerWrapperMap<EditorMouseListener> myEditorMouseListeners = new ListenerWrapperMap<EditorMouseListener>();
  public void addEditorMouseListener(@NotNull final EditorMouseListener listener) {
    assert isValid();
    EditorMouseListener wrapper = new EditorMouseListener() {
      public void mousePressed(EditorMouseEvent e) {
        listener.mousePressed(new EditorMouseEvent(EditorWindow.this, e.getMouseEvent(), e.getArea()));
      }

      public void mouseClicked(EditorMouseEvent e) {
        listener.mouseClicked(new EditorMouseEvent(EditorWindow.this, e.getMouseEvent(), e.getArea()));
      }

      public void mouseReleased(EditorMouseEvent e) {
        listener.mouseReleased(new EditorMouseEvent(EditorWindow.this, e.getMouseEvent(), e.getArea()));
      }

      public void mouseEntered(EditorMouseEvent e) {
        listener.mouseEntered(new EditorMouseEvent(EditorWindow.this, e.getMouseEvent(), e.getArea()));
      }

      public void mouseExited(EditorMouseEvent e) {
        listener.mouseExited(new EditorMouseEvent(EditorWindow.this, e.getMouseEvent(), e.getArea()));
      }
    };
    myEditorMouseListeners.registerWrapper(listener, wrapper);

    myDelegate.addEditorMouseListener(wrapper);
  }

  public void removeEditorMouseListener(@NotNull final EditorMouseListener listener) {
    EditorMouseListener wrapper = myEditorMouseListeners.removeWrapper(listener);
    // HintManager might have an old editor instance
    if (wrapper != null) {
      myDelegate.removeEditorMouseListener(wrapper);
    }
  }

  private final ListenerWrapperMap<EditorMouseMotionListener> myEditorMouseMotionListeners = new ListenerWrapperMap<EditorMouseMotionListener>();
  public void addEditorMouseMotionListener(@NotNull final EditorMouseMotionListener listener) {
    assert isValid();
    EditorMouseMotionListener wrapper = new EditorMouseMotionListener() {
      public void mouseMoved(EditorMouseEvent e) {
        listener.mouseMoved(new EditorMouseEvent(EditorWindow.this, e.getMouseEvent(), e.getArea()));
      }

      public void mouseDragged(EditorMouseEvent e) {
        listener.mouseDragged(new EditorMouseEvent(EditorWindow.this, e.getMouseEvent(), e.getArea()));
      }
    };
    myEditorMouseMotionListeners.registerWrapper(listener, wrapper);
    myDelegate.addEditorMouseMotionListener(wrapper);
  }

  public void removeEditorMouseMotionListener(@NotNull final EditorMouseMotionListener listener) {
    EditorMouseMotionListener wrapper = myEditorMouseMotionListeners.removeWrapper(listener);
    if (wrapper != null) {
      myDelegate.removeEditorMouseMotionListener(wrapper);
    }
  }

  public boolean isDisposed() {
    return !myDisposed && myDelegate.isDisposed();
  }

  public void setBackgroundColor(final Color color) {
    myDelegate.setBackgroundColor(color);
  }

  public void resetBackgourndColor() {
    myDelegate.resetBackgourndColor();
  }

  public Color getBackroundColor() {
    return myDelegate.getBackroundColor();
  }

  public int getMaxWidthInRange(final int startOffset, final int endOffset) {
    return myDelegate.getMaxWidthInRange(startOffset, endOffset);
  }

  public int getLineHeight() {
    return myDelegate.getLineHeight();
  }

  public Dimension getContentSize() {
    return myDelegate.getContentSize();
  }

  public JScrollPane getScrollPane() {
    return myDelegate.getScrollPane();
  }

  public int logicalPositionToOffset(@NotNull final LogicalPosition pos) {
    return myDocumentWindow.getLineStartOffset(pos.line) + calcColumnNumber(pos.column, pos.line);
  }
  private int calcColumnNumber(int offset, int lineIndex) {
    if (myDocumentWindow.getTextLength() == 0) return 0;

    CharSequence text = myDocumentWindow.getCharsSequence();
    int start = myDocumentWindow.getLineStartOffset(lineIndex);

    if (offset==0) return 0;
    int end = myDocumentWindow.getLineEndOffset(lineIndex);
    if (offset > end-start) offset = end - start;
    return EditorUtil.calcColumnNumber(this, text, start, start+offset, myDelegate.getTabSize());
  }


  public void setLastColumnNumber(final int val) {
    myDelegate.setLastColumnNumber(val);
  }

  public int getLastColumnNumber() {
    return myDelegate.getLastColumnNumber();
  }


  // assuming there is no folding in injected documents
  @NotNull
  public VisualPosition logicalToVisualPosition(@NotNull final LogicalPosition pos) {
    assert isValid();
    return new VisualPosition(pos.line, pos.column);
  }

  @NotNull
  public LogicalPosition visualToLogicalPosition(@NotNull final VisualPosition pos) {
    assert isValid();
    return new LogicalPosition(pos.line, pos.column);
  }

  public DataContext getDataContext() {
    return myDelegate.getDataContext();
  }

  public EditorMouseEventArea getMouseEventArea(@NotNull final MouseEvent e) {
    return myDelegate.getMouseEventArea(e);
  }

  public void setCaretVisible(final boolean b) {
    myDelegate.setCaretVisible(b);
  }

  public void addFocusListener(final FocusChangeListener listener) {
    myDelegate.addFocusListener(listener);
  }

  public Project getProject() {
    return myDelegate.getProject();
  }

  public boolean isOneLineMode() {
    return myOneLine;
  }

  public void setOneLineMode(final boolean isOneLineMode) {
    throw new UnsupportedOperationException();
  }

  public boolean isEmbeddedIntoDialogWrapper() {
    return myDelegate.isEmbeddedIntoDialogWrapper();
  }

  public void setEmbeddedIntoDialogWrapper(final boolean b) {
    myDelegate.setEmbeddedIntoDialogWrapper(b);
  }

  public VirtualFile getVirtualFile() {
    return myDelegate.getVirtualFile();
  }

  public void stopOptimizedScrolling() {
    myDelegate.stopOptimizedScrolling();
  }

  public CopyProvider getCopyProvider() {
    return myDelegate.getCopyProvider();
  }

  public CutProvider getCutProvider() {
    return myDelegate.getCutProvider();
  }

  public PasteProvider getPasteProvider() {
    return myDelegate.getPasteProvider();
  }

  public DeleteProvider getDeleteProvider() {
    return myDelegate.getDeleteProvider();
  }

  public void setColorsScheme(@NotNull final EditorColorsScheme scheme) {
    myDelegate.setColorsScheme(scheme);
  }

  @NotNull
  public EditorColorsScheme getColorsScheme() {
    return myDelegate.getColorsScheme();
  }

  public void setVerticalScrollbarOrientation(final int type) {
    myDelegate.setVerticalScrollbarOrientation(type);
  }

  public void setVerticalScrollbarVisible(final boolean b) {
    myDelegate.setVerticalScrollbarVisible(b);
  }

  public void setHorizontalScrollbarVisible(final boolean b) {
    myDelegate.setHorizontalScrollbarVisible(b);
  }

  public boolean processKeyTyped(final KeyEvent e) {
    return myDelegate.processKeyTyped(e);
  }

  @NotNull
  public EditorGutter getGutter() {
    return myDelegate.getGutter();
  }

  public <T> T getUserData(final Key<T> key) {
    return myDelegate.getUserData(key);
  }

  public <T> void putUserData(final Key<T> key, final T value) {
    myDelegate.putUserData(key, value);
  }

  @NotNull
  public <T> T putUserDataIfAbsent(@NotNull final Key<T> key, @NotNull final T value) {
    return myDelegate.putUserDataIfAbsent(key, value);
  }

  public <T> boolean replace(@NotNull Key<T> key, @NotNull T oldValue, @Nullable T newValue) {
    return myDelegate.replace(key, oldValue, newValue);
  }

  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    final EditorWindow that = (EditorWindow)o;

    DocumentWindow thatWindow = that.getDocument();
    return myDelegate.equals(that.myDelegate) && myDocumentWindow.equals(thatWindow);
  }

  public int hashCode() {
    return myDocumentWindow.hashCode();
  }

  public Editor getDelegate() {
    return myDelegate;
  }

  public int calcColumnNumber(final CharSequence text, final int start, final int offset, final int tabSize) {
    int hostStart = myDocumentWindow.injectedToHost(start);
    int hostOffset = myDocumentWindow.injectedToHost(offset);
    return myDelegate.calcColumnNumber(myDelegate.getDocument().getText(), hostStart, hostOffset, tabSize);
  }
}