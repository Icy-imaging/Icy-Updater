/**
 * 
 */
package updater;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.HeadlessException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.PrintStream;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/**
 * @author stephane
 */
public class UpdateFrame extends JFrame
{
    class OutPrintStream extends PrintStream
    {
        boolean isStdErr;

        public OutPrintStream(PrintStream out, boolean isStdErr)
        {
            super(out);

            this.isStdErr = isStdErr;
        }

        @Override
        public void write(byte[] buf, int off, int len)
        {
            super.write(buf, off, len);

            if (buf == null)
            {
                throw new NullPointerException();
            }
            else if ((off < 0) || (off > buf.length) || (len < 0) || ((off + len) > buf.length) || ((off + len) < 0))
            {
                throw new IndexOutOfBoundsException();
            }
            else if (len == 0)
            {
                return;
            }

            addMessage(new String(buf, off, len), isStdErr);
        }
    }

    /**
     * 
     */
    private static final long serialVersionUID = -1849973451683594479L;

    /**
     * gui
     */
    final JLabel title = new JLabel();
    final JProgressBar progress = new JProgressBar();
    final JTextArea infos = new JTextArea();
    final JButton closeBtn = new JButton("close");

    /**
     * 
     */
    private final OutPrintStream stdStream = new OutPrintStream(System.out, false);
    private final OutPrintStream errStream = new OutPrintStream(System.err, true);

    /**
     * @param title
     * @throws HeadlessException
     */
    public UpdateFrame(String title) throws HeadlessException
    {
        super(title);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setDefaultLookAndFeelDecorated(true);

        setMinimumSize(new Dimension(640, 300));
        setPreferredSize(new Dimension(640, 300));
        setLocation(150, 150);

        build();

        // redirect stdOut and errOut
        System.setOut(stdStream);
        System.setErr(errStream);
    }

    public void build()
    {
        setLayout(new BoxLayout(getContentPane(), BoxLayout.PAGE_AXIS));

        final JPanel mainPanel = new JPanel();

        mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.PAGE_AXIS));

        final JPanel labelPanel = new JPanel();
        final JPanel progressPanel = new JPanel();
        final JPanel infoPanel = new JPanel();
        final JPanel buttonPanel = new JPanel();

        labelPanel.setLayout(new BoxLayout(labelPanel, BoxLayout.LINE_AXIS));
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.LINE_AXIS));
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.LINE_AXIS));
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));

        title.setText("Waiting shutdown, please wait...");
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setHorizontalTextPosition(SwingConstants.CENTER);

        infos.setText("");
        infos.setEditable(false);
        infos.setMinimumSize(new Dimension(540, 160));
        infos.setPreferredSize(new Dimension(540, 160));
        infos.setLineWrap(true);

        progress.setMinimum(0);
        progress.setMaximum(100);

        closeBtn.setEnabled(false);
        closeBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                // close frame
                UpdateFrame.this.dispose();
            }
        });

        labelPanel.add(Box.createHorizontalStrut(30));
        labelPanel.add(Box.createHorizontalGlue());
        labelPanel.add(title);
        labelPanel.add(Box.createHorizontalGlue());
        labelPanel.add(Box.createHorizontalStrut(30));

        progressPanel.add(Box.createHorizontalStrut(10));
        progressPanel.add(Box.createHorizontalGlue());
        progressPanel.add(progress);
        progressPanel.add(Box.createHorizontalGlue());
        progressPanel.add(Box.createHorizontalStrut(10));

        infoPanel.add(Box.createHorizontalStrut(5));
        infoPanel.add(Box.createHorizontalGlue());
        infoPanel.add(new JScrollPane(infos));
        infoPanel.add(Box.createHorizontalGlue());
        infoPanel.add(Box.createHorizontalStrut(5));

        buttonPanel.add(Box.createHorizontalGlue());
        buttonPanel.add(closeBtn);
        buttonPanel.add(Box.createHorizontalGlue());

        mainPanel.add(labelPanel);
        mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(progressPanel);
        mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(infoPanel);
        mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(buttonPanel);

        add(mainPanel);

        pack();
        validate();
    }

    @Override
    public void setTitle(String text)
    {
        title.setText(text);
    }

    public void setCanClose(boolean value)
    {
        closeBtn.setEnabled(value);
    }

    public void addMessage(String message, boolean error)
    {
        SimpleAttributeSet set = new SimpleAttributeSet();

        if (error)
        {
            StyleConstants.setForeground(set, Color.red);
            // force frame visibility if error
            setVisible(true);
        }
        else
            StyleConstants.setForeground(set, Color.black);

        try
        {
            infos.getDocument().insertString(infos.getDocument().getLength(), message, set);
            infos.setCaretPosition(infos.getDocument().getLength());
        }
        catch (BadLocationException e)
        {
            // ignore
        }
    }

    public void setProgress(int value)
    {
        progress.setValue(value);
    }

    public void setProgressVisible(boolean value)
    {
        progress.setVisible(value);
    }

}
