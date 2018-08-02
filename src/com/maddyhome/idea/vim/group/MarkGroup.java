/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2016 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.group;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.maddyhome.idea.vim.EventFacade;
import com.maddyhome.idea.vim.VimPlugin;
import com.maddyhome.idea.vim.command.Command;
import com.maddyhome.idea.vim.command.CommandState;
import com.maddyhome.idea.vim.common.Jump;
import com.maddyhome.idea.vim.common.Mark;
import com.maddyhome.idea.vim.common.TextRange;
import com.maddyhome.idea.vim.helper.EditorData;
import com.maddyhome.idea.vim.helper.EditorHelper;
import com.maddyhome.idea.vim.helper.SearchHelper;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * This class contains all the mark related functionality
 */
public class MarkGroup {
  public static final char MARK_VISUAL_START = '<';
  public static final char MARK_VISUAL_END = '>';
  public static final char MARK_CHANGE_START = '[';
  public static final char MARK_CHANGE_END = ']';
  public static final char MARK_CHANGE_POS = '.';

  /**
   * Creates the class
   */
  public MarkGroup() {
    EventFacade.getInstance().addEditorFactoryListener(new EditorFactoryAdapter() {
      public void editorReleased(@NotNull EditorFactoryEvent event) {
        // Save off the last caret position of the file before it is closed
        Editor editor = event.getEditor();
        setMark(editor, '"', editor.getCaretModel().getOffset());
      }
    }, ApplicationManager.getApplication());
  }

  /**
   * Saves the caret location prior to doing a jump
   *
   * @param editor  The editor the jump will occur in
   */
  public void saveJumpLocation(@NotNull Editor editor) {
    addJump(editor, true);
    setMark(editor, '\'');
  }

  /**
   * Gets the requested mark for the editor
   *
   * @param editor The editor to get the mark for
   * @param ch     The desired mark
   * @return The requested mark if set, null if not set
   */
  @Nullable
  public Mark getMark(@NotNull Editor editor, char ch) {
    Mark mark = null;
    if (ch == '`') ch = '\'';

    // Make sure this is a valid mark
    if (VALID_GET_MARKS.indexOf(ch) < 0) return null;

    VirtualFile vf = EditorData.getVirtualFile(editor);
    if ("{}".indexOf(ch) >= 0 && vf != null) {
      int offset = SearchHelper.findNextParagraph(editor, editor.getCaretModel().getPrimaryCaret(), ch == '{' ? -1 : 1,
                                                  false);
      offset = EditorHelper.normalizeOffset(editor, offset, false);
      LogicalPosition lp = editor.offsetToLogicalPosition(offset);
      mark = new Mark(ch, lp.line, lp.column, vf.getPath(), extractProtocol(vf));
    }
    else if ("()".indexOf(ch) >= 0 && vf != null) {
      int offset = SearchHelper.findNextSentenceStart(editor, editor.getCaretModel().getPrimaryCaret(),
                                                      ch == '(' ? -1 : 1, false, true);
      offset = EditorHelper.normalizeOffset(editor, offset, false);
      LogicalPosition lp = editor.offsetToLogicalPosition(offset);
      mark = new Mark(ch, lp.line, lp.column, vf.getPath(), extractProtocol(vf));
    }
    // If this is a file mark, get the mark from this file
    else if (FILE_MARKS.indexOf(ch) >= 0) {
      final HashMap fmarks = getFileMarks(editor.getDocument());
      if (fmarks != null) {
        mark = (Mark)fmarks.get(ch);
        if (mark != null && mark.isClear()) {
          fmarks.remove(ch);
          mark = null;
        }
      }
    }
    // This is a mark from another file
    else if (GLOBAL_MARKS.indexOf(ch) >= 0) {
      mark = globalMarks.get(ch);
      if (mark != null && mark.isClear()) {
        globalMarks.remove(ch);
        mark = null;
      }
    }

    return mark;
  }

  /**
   * Get the requested jump.
   *
   * @param count Postive for next jump (Ctrl-I), negative for previous jump (Ctrl-O).
   * @return The jump or null if out of range.
   */
  @Nullable
  public Jump getJump(int count) {
    int index = jumps.size() - 1 - (jumpSpot - count);
    if (index < 0 || index >= jumps.size()) {
      return null;
    }
    else {
      jumpSpot -= count;
      return jumps.get(index);
    }
  }

  /**
   * Get's a mark from the file
   *
   * @param editor The editor to get the mark from
   * @param ch     The mark to get
   * @return The mark in the current file, if set, null if no such mark
   */
  @Nullable
  public Mark getFileMark(@NotNull Editor editor, char ch) {
    if (ch == '`') ch = '\'';
    final HashMap fmarks = getFileMarks(editor.getDocument());
    if (fmarks == null) {
      return null;
    }
    Mark mark = (Mark)fmarks.get(ch);
    if (mark != null && mark.isClear()) {
      fmarks.remove(ch);
      mark = null;
    }

    return mark;
  }

  /**
   * Sets the specified mark to the caret position of the editor
   *
   * @param editor  The editor to get the current position from
   * @param ch      The mark set set
   * @return True if a valid, writable mark, false if not
   */
  public boolean setMark(@NotNull Editor editor, char ch) {
    return VALID_SET_MARKS.indexOf(ch) >= 0 && setMark(editor, ch, editor.getCaretModel().getOffset());
  }

  /**
   * Sets the specified mark to the specified location.
   *
   * @param editor  The editor the mark is associated with
   * @param ch      The mark to set
   * @param offset  The offset to set the mark to
   * @return true if able to set the mark, false if not
   */
  public boolean setMark(@NotNull Editor editor, char ch, int offset) {
    if (ch == '`') ch = '\'';
    LogicalPosition lp = editor.offsetToLogicalPosition(offset);

    final VirtualFile vf = EditorData.getVirtualFile(editor);
    if (vf == null) {
      return false;
    }

    Mark mark = new Mark(ch, lp.line, lp.column, vf.getPath(), extractProtocol(vf));
    // File specific marks get added to the file
    if (FILE_MARKS.indexOf(ch) >= 0) {
      HashMap<Character, Mark> fmarks = getFileMarks(editor.getDocument());
      if (fmarks == null) {
        return false;
      }
      fmarks.put(ch, mark);
    }
    // Global marks get set to both the file and the global list of marks
    else if (GLOBAL_MARKS.indexOf(ch) >= 0) {
      HashMap<Character, Mark> fmarks = getFileMarks(editor.getDocument());
      if (fmarks == null) {
        return false;
      }
      fmarks.put(ch, mark);
      Mark oldMark = globalMarks.put(ch, mark);
      if (oldMark != null) {
        oldMark.clear();
      }
    }

    return true;
  }

  private String extractProtocol(@NotNull VirtualFile vf) {
    return VirtualFileManager.extractProtocol(vf.getUrl());
  }

  public void setVisualSelectionMarks(@NotNull Editor editor, @NotNull TextRange range) {
    setMark(editor, MARK_VISUAL_START, range.getStartOffset());
    setMark(editor, MARK_VISUAL_END, range.getEndOffset());
  }

  public void setChangeMarks(@NotNull Editor editor, @NotNull TextRange range) {
    setMark(editor, MARK_CHANGE_START, range.getStartOffset());
    setMark(editor, MARK_CHANGE_END, range.getEndOffset());
  }

  @Nullable
  public TextRange getChangeMarks(@NotNull Editor editor) {
    return getMarksRange(editor, MARK_CHANGE_START, MARK_CHANGE_END);
  }

  @Nullable
  public TextRange getVisualSelectionMarks(@NotNull Editor editor) {
    return getMarksRange(editor, MARK_VISUAL_START, MARK_VISUAL_END);
  }

  @Nullable
  private TextRange getMarksRange(@NotNull Editor editor, char startMark, char endMark) {
    final Mark start = getMark(editor, startMark);
    final Mark end = getMark(editor, endMark);
    if (start != null && end != null) {
      final int startOffset = EditorHelper.getOffset(editor, start.getLogicalLine(), start.getCol());
      final int endOffset = EditorHelper.getOffset(editor, end.getLogicalLine(), end.getCol());
      return new TextRange(startOffset, endOffset);
    }
    return null;
  }

  public void addJump(@NotNull Editor editor, boolean reset) {
    addJump(editor, editor.getCaretModel().getOffset(), reset);
  }

  private void addJump(@NotNull Editor editor, int offset, boolean reset) {
    final VirtualFile vf = EditorData.getVirtualFile(editor);
    if (vf == null) {
      return;
    }

    LogicalPosition lp = editor.offsetToLogicalPosition(offset);
    Jump jump = new Jump(lp.line, lp.column, vf.getPath());
    final String filename = jump.getFilename();

    for (int i = 0; i < jumps.size(); i++) {
      Jump j = jumps.get(i);
      if (filename != null && filename.equals(j.getFilename()) && j.getLogicalLine() == jump.getLogicalLine()) {
        jumps.remove(i);
        break;
      }
    }

    jumps.add(jump);

    if (reset) {
      jumpSpot = -1;
    }
    else {
      jumpSpot++;
    }

    if (jumps.size() > SAVE_JUMP_COUNT) {
      jumps.remove(0);
    }
  }

  private void removeMark(char ch, @NotNull Mark mark) {
    if (FILE_MARKS.indexOf(ch) >= 0) {
      HashMap fmarks = getFileMarks(mark.getFilename());
      fmarks.remove(ch);
    }
    else if (GLOBAL_MARKS.indexOf(ch) >= 0) {
      globalMarks.remove(ch);
    }

    mark.clear();
  }

  @NotNull
  public List<Mark> getMarks(@NotNull Editor editor) {
    HashSet<Mark> res = new HashSet<>();

    final FileMarks<Character, Mark> marks = getFileMarks(editor.getDocument());
    if (marks != null) {
      res.addAll(marks.values());
    }
    res.addAll(globalMarks.values());

    ArrayList<Mark> list = new ArrayList<>(res);

    list.sort(new Mark.KeySorter<>());

    return list;
  }

  @NotNull
  public List<Jump> getJumps() {
    return jumps;
  }

  public int getJumpSpot() {
    return jumpSpot;
  }

  /**
   * Gets the map of marks for the specified file
   *
   * @param doc The editor to get the marks for
   * @return The map of marks. The keys are <code>Character</code>s of the mark names, the values are
   *         <code>Mark</code>s.
   */
  @Nullable
  private FileMarks<Character, Mark> getFileMarks(@NotNull final Document doc) {
    VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
    if (vf == null) {
      return null;
    }

    return getFileMarks(vf.getPath());
  }

  @Nullable
  private HashMap<Character, Mark> getAllFileMarks(@NotNull final Document doc) {
    VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
    if (vf == null) {
      return null;
    }

    HashMap<Character, Mark> res = new HashMap<>();
    FileMarks<Character, Mark> fileMarks = getFileMarks(doc);
    if (fileMarks != null) {
      res.putAll(fileMarks);
    }

    for (Character ch : globalMarks.keySet()) {
      Mark mark = globalMarks.get(ch);
      if (vf.getPath().equals(mark.getFilename())) {
        res.put(ch, mark);
      }
    }

    return res;
  }

  /**
   * Gets the map of marks for the specified file
   *
   * @param filename The file to get the marks for
   * @return The map of marks. The keys are <code>Character</code>s of the mark names, the values are
   *         <code>Mark</code>s.
   */
  private FileMarks<Character, Mark> getFileMarks(String filename) {
    FileMarks<Character, Mark> marks = fileMarks.get(filename);
    if (marks == null) {
      marks = new FileMarks<>();
      fileMarks.put(filename, marks);
    }

    return marks;
  }

  public void saveData(@NotNull Element element) {
    Element marksElem = new Element("globalmarks");
    for (Mark mark : globalMarks.values()) {
      if (!mark.isClear()) {
        Element markElem = new Element("mark");
        markElem.setAttribute("key", Character.toString(mark.getKey()));
        markElem.setAttribute("line", Integer.toString(mark.getLogicalLine()));
        markElem.setAttribute("column", Integer.toString(mark.getCol()));
        markElem.setAttribute("filename", StringUtil.notNullize(mark.getFilename()));
        markElem.setAttribute("protocol", StringUtil.notNullize(mark.getProtocol(), "file"));
        marksElem.addContent(markElem);
        if (logger.isDebugEnabled()) {
          logger.debug("saved mark = " + mark);
        }
      }
    }
    element.addContent(marksElem);

    Element fileMarksElem = new Element("filemarks");

    List<FileMarks<Character, Mark>> files = new ArrayList<>(fileMarks.values());
    files.sort(Comparator.comparing(o -> o.timestamp));

    if (files.size() > SAVE_MARK_COUNT) {
      files = files.subList(files.size() - SAVE_MARK_COUNT, files.size());
    }

    for (String file : fileMarks.keySet()) {
      FileMarks<Character, Mark> marks = fileMarks.get(file);
      if (!files.contains(marks)) {
        continue;
      }

      if (marks.size() > 0) {
        Element fileMarkElem = new Element("file");
        fileMarkElem.setAttribute("name", file);
        fileMarkElem.setAttribute("timestamp", Long.toString(marks.timestamp.getTime()));
        for (Mark mark : marks.values()) {
          if (!mark.isClear() && !Character.isUpperCase(mark.getKey()) &&
              SAVE_FILE_MARKS.indexOf(mark.getKey()) >= 0) {
            Element markElem = new Element("mark");
            markElem.setAttribute("key", Character.toString(mark.getKey()));
            markElem.setAttribute("line", Integer.toString(mark.getLogicalLine()));
            markElem.setAttribute("column", Integer.toString(mark.getCol()));
            fileMarkElem.addContent(markElem);
          }
        }
        fileMarksElem.addContent(fileMarkElem);
      }
    }
    element.addContent(fileMarksElem);

    Element jumpsElem = new Element("jumps");
    for (Jump jump : jumps) {
      if (!jump.isClear()) {
        Element jumpElem = new Element("jump");
        jumpElem.setAttribute("line", Integer.toString(jump.getLogicalLine()));
        jumpElem.setAttribute("column", Integer.toString(jump.getCol()));
        jumpElem.setAttribute("filename", StringUtil.notNullize(jump.getFilename()));
        jumpsElem.addContent(jumpElem);
        if (logger.isDebugEnabled()) {
          logger.debug("saved jump = " + jump);
        }
      }
    }
    element.addContent(jumpsElem);
  }

  public void readData(@NotNull Element element) {
    // We need to keep the filename for now and create the virtual file later. Any attempt to call
    // LocalFileSystem.getInstance().findFileByPath() results in the following error:
    // Read access is allowed from event dispatch thread or inside read-action only
    // (see com.intellij.openapi.application.Application.runReadAction())

    Element marksElem = element.getChild("globalmarks");
    if (marksElem != null) {
      List markList = marksElem.getChildren("mark");
      for (Object aMarkList : markList) {
        Element markElem = (Element)aMarkList;
        Mark mark = new Mark(markElem.getAttributeValue("key").charAt(0),
                             Integer.parseInt(markElem.getAttributeValue("line")),
                             Integer.parseInt(markElem.getAttributeValue("column")),
                             markElem.getAttributeValue("filename"),
                             markElem.getAttributeValue("protocol"));

        globalMarks.put(mark.getKey(), mark);
        HashMap<Character, Mark> fmarks = getFileMarks(mark.getFilename());
        fmarks.put(mark.getKey(), mark);
      }
    }

    if (logger.isDebugEnabled()) {
      logger.debug("globalMarks=" + globalMarks);
    }

    Element fileMarksElem = element.getChild("filemarks");
    if (fileMarksElem != null) {
      List fileList = fileMarksElem.getChildren("file");
      for (Object aFileList : fileList) {
        Element fileElem = (Element)aFileList;
        String filename = fileElem.getAttributeValue("name");
        Date timestamp = new Date();
        try {
          long date = Long.parseLong(fileElem.getAttributeValue("timestamp"));
          timestamp.setTime(date);
        }
        catch (NumberFormatException e) {
          // ignore
        }
        FileMarks<Character, Mark> fmarks = getFileMarks(filename);
        List markList = fileElem.getChildren("mark");
        for (Object aMarkList : markList) {
          Element markElem = (Element)aMarkList;
          Mark mark = new Mark(markElem.getAttributeValue("key").charAt(0),
                               Integer.parseInt(markElem.getAttributeValue("line")),
                               Integer.parseInt(markElem.getAttributeValue("column")),
                               filename,
                               markElem.getAttributeValue("protocol"));

          fmarks.put(mark.getKey(), mark);
        }
        fmarks.setTimestamp(timestamp);
      }
    }

    if (logger.isDebugEnabled()) {
      logger.debug("fileMarks=" + fileMarks);
    }

    jumps.clear();
    Element jumpsElem = element.getChild("jumps");
    if (jumpsElem != null) {
      List jumpList = jumpsElem.getChildren("jump");
      for (Object aJumpList : jumpList) {
        Element jumpElem = (Element)aJumpList;
        Jump jump = new Jump(Integer.parseInt(jumpElem.getAttributeValue("line")),
                             Integer.parseInt(jumpElem.getAttributeValue("column")),
                             jumpElem.getAttributeValue("filename"));

        jumps.add(jump);
      }
    }

    if (logger.isDebugEnabled()) {
      logger.debug("jumps=" + jumps);
    }
  }

  /**
   * This updates all the marks for a file whenever text is deleted from the file. If the line that contains a mark
   * is completely deleted then the mark is deleted too. If the deleted text is before the marked line, the mark is
   * moved up by the number of deleted lines.
   *
   * @param editor      The modified editor
   * @param marks       The editor's marks to update
   * @param delStartOff The offset within the editor where the deletion occurred
   * @param delLength   The length of the deleted text
   */
  public static void updateMarkFromDelete(@Nullable Editor editor, @Nullable HashMap<Character, Mark> marks, int delStartOff, int delLength) {
    // Skip all this work if there are no marks
    if (marks != null && marks.size() > 0 && editor != null) {
      // Calculate the logical position of the start and end of the deleted text
      int delEndOff = delStartOff + delLength - 1;
      LogicalPosition delStart = editor.offsetToLogicalPosition(delStartOff);
      LogicalPosition delEnd = editor.offsetToLogicalPosition(delEndOff + 1);
      if (logger.isDebugEnabled()) logger.debug("mark delete. delStart = " + delStart + ", delEnd = " + delEnd);

      // Now analyze each mark to determine if it needs to be updated or removed
      for (Character ch : marks.keySet()) {
        Mark mark = marks.get(ch);

        if (logger.isDebugEnabled()) logger.debug("mark = " + mark);
        // If the end of the deleted text is prior to the marked line, simply shift the mark up by the
        // proper number of lines.
        if (delEnd.line < mark.getLogicalLine()) {
          int lines = delEnd.line - delStart.line;
          if (logger.isDebugEnabled()) logger.debug("Shifting mark by " + lines + " lines");
          mark.setLogicalLine(mark.getLogicalLine() - lines);
        }
        // If the deleted text begins before the mark and ends after the mark then it may be shifted or deleted
        else if (delStart.line <= mark.getLogicalLine() && delEnd.line >= mark.getLogicalLine()) {
          int markLineStartOff = EditorHelper.getLineStartOffset(editor, mark.getLogicalLine());
          int markLineEndOff = EditorHelper.getLineEndOffset(editor, mark.getLogicalLine(), true);

          Command command = CommandState.getInstance(editor).getCommand();
          // If text is being changed from the start of the mark line (a special case for mark deletion)
          boolean changeFromMarkLineStart = command != null && command.getType() == Command.Type.CHANGE
                                            && delStartOff == markLineStartOff;
          // If the marked line is completely within the deleted text, remove the mark (except the special case)
          if (delStartOff <= markLineStartOff && delEndOff >= markLineEndOff && !changeFromMarkLineStart) {
            VimPlugin.getMark().removeMark(ch, mark);
            logger.debug("Removed mark");
          }
          // The deletion only covers part of the marked line so shift the mark only if the deletion begins
          // on a line prior to the marked line (which means the deletion must end on the marked line).
          else if (delStart.line < mark.getLogicalLine()) {
            // shift mark
            mark.setLogicalLine(delStart.line);
            if (logger.isDebugEnabled()) logger.debug("Shifting mark to line " + delStart.line);
          }
        }
      }
    }
  }

  /**
   * This updates all the marks for a file whenever text is inserted into the file. If the line that contains a mark
   * that is after the start of the insertion point, shift the mark by the number of new lines added.
   *
   * @param editor      The editor that was updated
   * @param marks       The editor's marks
   * @param insStartOff The insertion point
   * @param insLength   The length of the insertion
   */
  public static void updateMarkFromInsert(@Nullable Editor editor, @Nullable HashMap<Character, Mark> marks, int insStartOff, int insLength) {
    if (marks != null && marks.size() > 0 && editor != null) {
      int insEndOff = insStartOff + insLength;
      LogicalPosition insStart = editor.offsetToLogicalPosition(insStartOff);
      LogicalPosition insEnd = editor.offsetToLogicalPosition(insEndOff);
      if (logger.isDebugEnabled()) logger.debug("mark insert. insStart = " + insStart + ", insEnd = " + insEnd);
      int lines = insEnd.line - insStart.line;
      if (lines == 0) return;

      for (Mark mark : marks.values()) {
        if (logger.isDebugEnabled()) logger.debug("mark = " + mark);
        // Shift the mark if the insertion began on a line prior to the marked line.
        if (insStart.line < mark.getLogicalLine()) {
          mark.setLogicalLine(mark.getLogicalLine() + lines);
          if (logger.isDebugEnabled()) logger.debug("Shifting mark by " + lines + " lines");
        }
      }
    }
  }

  private static class FileMarks<K, V> extends HashMap<K, V> {
    public void setTimestamp(Date timestamp) {
      this.timestamp = timestamp;
    }

    public V put(K key, V value) {
      timestamp = new Date();
      return super.put(key, value);
    }

    private Date timestamp = new Date();
  }

  /**
   * This class is used to listen to editor document changes
   */
  public static class MarkUpdater implements DocumentListener {
    /**
     * Creates the listener for the supplied editor
     */
    public MarkUpdater() {
    }

    /**
     * This event indicates that a document is about to be changed. We use this event to update all the
     * editor's marks if text is about to be deleted.
     *
     * @param event The change event
     */
    public void beforeDocumentChange(@NotNull DocumentEvent event) {
      if (!VimPlugin.isEnabled()) return;

      if (logger.isDebugEnabled()) logger.debug("MarkUpdater before, event = " + event);
      if (event.getOldLength() == 0) return;

      Document doc = event.getDocument();
      updateMarkFromDelete(getAnEditor(doc), VimPlugin.getMark().getAllFileMarks(doc), event.getOffset(),
                           event.getOldLength());
      // TODO - update jumps
    }

    /**
     * This event indicates that a document was just changed. We use this event to update all the editor's
     * marks if text was just added.
     *
     * @param event The change event
     */
    public void documentChanged(@NotNull DocumentEvent event) {
      if (!VimPlugin.isEnabled()) return;

      if (logger.isDebugEnabled()) logger.debug("MarkUpdater after, event = " + event);
      if (event.getNewLength() == 0 || (event.getNewLength() == 1 && event.getNewFragment().charAt(0) != '\n')) return;

      Document doc = event.getDocument();
      updateMarkFromInsert(getAnEditor(doc), VimPlugin.getMark().getAllFileMarks(doc), event.getOffset(),
                           event.getNewLength());
      // TODO - update jumps
    }

    @Nullable
    private Editor getAnEditor(@NotNull Document doc) {
      Editor[] editors = EditorFactory.getInstance().getEditors(doc);

      if (editors.length > 0) {
        return editors[0];
      }
      else {
        return null;
      }
    }
  }

  @NotNull private final HashMap<String, FileMarks<Character, Mark>> fileMarks = new HashMap<>();
  @NotNull private final HashMap<Character, Mark> globalMarks = new HashMap<>();
  @NotNull private final List<Jump> jumps = new ArrayList<>();
  private int jumpSpot = -1;

  private static final int SAVE_MARK_COUNT = 20;
  private static final int SAVE_JUMP_COUNT = 100;

  private static final String WR_GLOBAL_MARKS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String WR_FILE_MARKS = "abcdefghijklmnopqrstuvwxyz'";
  private static final String RO_GLOBAL_MARKS = "0123456789";
  private static final String RO_FILE_MARKS = ".[]<>^{}()";
  private static final String SAVE_FILE_MARKS = WR_FILE_MARKS + ".^[]\"";

  private static final String GLOBAL_MARKS = WR_GLOBAL_MARKS + RO_GLOBAL_MARKS;
  private static final String FILE_MARKS = WR_FILE_MARKS + RO_FILE_MARKS;

  private static final String WRITE_MARKS = WR_GLOBAL_MARKS + WR_FILE_MARKS;
  private static final String READONLY_MARKS = RO_GLOBAL_MARKS + RO_FILE_MARKS;

  private static final String VALID_SET_MARKS = WRITE_MARKS;
  private static final String VALID_GET_MARKS = WRITE_MARKS + READONLY_MARKS;

  private static final Logger logger = Logger.getInstance(MarkGroup.class.getName());
}
