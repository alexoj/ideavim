/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2020 The IdeaVim authors
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.ui;

import com.intellij.openapi.editor.Editor;
import com.maddyhome.idea.vim.helper.UiHelper;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyListener;

/**
 * @author vlan
 */
public class ModalEntryDialog extends JDialog {
  private final @NotNull JTextField myEntry;
  private final @NotNull JLabel myLabel;
  private final @NotNull JComponent myParent;

  public ModalEntryDialog(@NotNull Editor editor, @NotNull String prompt) {
    super((Frame)null, true);
    myParent = editor.getContentComponent();
    myLabel = new JLabel(prompt);
    myEntry = new JTextField();

    myEntry.setBorder(null);

    myLabel.setFont(UiHelper.selectFont(myLabel.getText()));
    myEntry.setFont(UiHelper.selectFont(myEntry.getText()));

    setForeground(myEntry.getForeground());
    setBackground(myEntry.getBackground());

    myLabel.setForeground(myEntry.getForeground());
    myLabel.setBackground(myEntry.getBackground());

    GridBagLayout layout = new GridBagLayout();
    GridBagConstraints gbc = new GridBagConstraints();

    setLayout(layout);
    gbc.gridx = 0;
    layout.setConstraints(myLabel, gbc);
    add(myLabel);
    gbc.gridx = 1;
    gbc.weightx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    layout.setConstraints(myEntry, gbc);
    add(myEntry);

    setUndecorated(true);

    final Container scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, myParent);
    final int height = (int)getPreferredSize().getHeight();
    if (scroll != null) {
      final Rectangle bounds = scroll.getBounds();
      bounds.translate(0, scroll.getHeight() - height);
      bounds.height = height;
      final JRootPane rootPane = SwingUtilities.getRootPane(myParent);
      final Point pos = SwingUtilities.convertPoint(scroll.getParent(), bounds.getLocation(), rootPane);
      final Window window = SwingUtilities.getWindowAncestor(myParent);
      final Point windowPos = window.getLocation();
      pos.translate(windowPos.x, windowPos.y);
      final Insets windowInsets = window.getInsets();
      pos.translate(windowInsets.left, windowInsets.top);
      bounds.setLocation(pos);
      setBounds(bounds);
    }
  }

  public void setEntryKeyListener(@NotNull KeyListener listener) {
    myEntry.addKeyListener(listener);
  }

  public @NotNull String getText() {
    return myEntry.getText();
  }
}
