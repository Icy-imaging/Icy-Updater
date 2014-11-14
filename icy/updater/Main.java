/**
 * 
 */
package icy.updater;

import icy.common.Version;
import icy.file.FileUtil;
import icy.network.NetworkUtil;
import icy.preferences.ApplicationPreferences;
import icy.preferences.IcyPreferences;
import icy.system.SystemUtil;
import icy.system.thread.ThreadUtil;
import icy.update.ElementDescriptor;
import icy.update.Updater;
import icy.util.StringUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * @author stephane
 */
public class Main
{
    // private static final String ICY_MAINCLASS = "icy.main.Icy";

    static class OutPrintStream extends PrintStream
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

            final String str = new String(buf, off, len);

            strLog += str;
            if (frame != null)
                frame.addMessage(str, isStdErr);
        }
    }

    /**
     * Keep for older version compatibility
     * 
     * @deprecated
     */
    @Deprecated
    private static final String ARG_NOUPDATE = "-noupdate";
    /**
     * Keep for older version compatibility
     * 
     * @deprecated
     */
    @Deprecated
    private static final String ARG_RESTART = "-restart";

    private static final String ICY_JARNAME = "icy.jar";

    private static final String PARAM_MAX_MEMORY = "-Xmx";
    private static final String PARAM_STACK_SIZE = "-Xss";

    /**
     * Updater Version
     */
    public static Version version = new Version("1.6.12.0");

    static final OutPrintStream stdStream = new OutPrintStream(System.out, false);
    static final OutPrintStream errStream = new OutPrintStream(System.err, true);

    static UpdateFrame frame = null;
    static String extraArgs = "";

    static String strLog = "";

    /**
     * @param args
     *        Received from the command line.
     */
    public static void main(String[] args)
    {
        boolean oldVersion = false;
        boolean start = true;
        boolean update;

        // load preferences
        IcyPreferences.init();
        // update proxy setting
        NetworkUtil.updateNetworkSetting();

        // detect if old ICY request update
        for (String arg : args)
            if (arg.equals(ARG_RESTART))
                oldVersion = true;

        if (oldVersion)
        {
            update = true;

            for (String arg : args)
                if (arg.equals(ARG_NOUPDATE))
                    update = false;
        }
        else
        {
            update = false;

            for (String arg : args)
            {
                if (arg.equals(Updater.ARG_UPDATE))
                    update = true;
                else if (arg.equals(Updater.ARG_NOSTART))
                    start = false;
            }
        }

        // keep trace of others arguments
        for (String arg : args)
        {
            if (!(arg.equals(Updater.ARG_UPDATE) || arg.equals(Updater.ARG_NOSTART) || arg.equals(ARG_NOUPDATE) || arg
                    .equals(ARG_RESTART)))
                extraArgs = extraArgs + " " + arg;
        }

        // redirect stdOut and errOut
        System.setOut(stdStream);
        System.setErr(errStream);

        // no error --> we can exit
        if (process(update, start))
        {
            if (frame != null)
            {
                // we got some error messages on starting so we wait for 5 seconds before closing frame
                if (!update && frame.isVisible())
                    ThreadUtil.sleep(5000);
                frame.dispose();
            }
            System.exit(0);
        }
        else if (frame != null)
            frame.setCanClose(true);
    }

    private static boolean process(final boolean update, final boolean start)
    {
        // prepare GUI
        ThreadUtil.invokeNow(new Runnable()
        {
            @Override
            public void run()
            {
                if (SystemUtil.isHeadLess())
                    frame = null;
                else
                {
                    frame = new UpdateFrame("Icy Updater");
                    // update --> show progress frame immediately
                    if (update)
                        frame.setVisible(true);
                }
            }
        });

        // get Icy directory and path
        final String directory = FileUtil.getApplicationDirectory();
        final String icyJarPath = directory + File.separator + ICY_JARNAME;

        // wait for lock
        if (!waitForLock(icyJarPath))
        {
            System.err.println("File " + icyJarPath + " is locked, aborting udpate...");

            // send report of the error
            report(strLog);

            return false;
        }

        final boolean result;

        if (update)
            result = doUpdate();
        else
            result = true;

        // start ICY
        if (result && start)
            return startICY(directory);

        return result;
    }

    /**
     * Process the update.<br>
     * Working directory should be the ICY directory else update won't work.
     */
    public static boolean doUpdate()
    {
        ArrayList<ElementDescriptor> localElements = Updater.getLocalElements();
        final ArrayList<ElementDescriptor> updateElements = Updater.getUpdateElements(localElements);
        boolean result = true;

        // get list of element to update
        for (ElementDescriptor updateElement : updateElements)
        {
            final String updateElementName = updateElement.getName();

            // can't update updater here (it should be already updated)
            if (updateElementName.equals(Updater.ICYUPDATER_NAME))
            {
                // just update local element with update element info
                Updater.updateElementInfos(updateElement, localElements);
                continue;
            }

            setState("Updating : " + updateElementName,
                    (updateElements.indexOf(updateElement) * 60) / updateElements.size());

            // update element
            if (!Updater.udpateElement(updateElement, localElements))
            {
                // an error happened --> take back current local elements
                localElements = Updater.getLocalElements();
                // remove the faulty element informations, this will force
                // update next time.
                Updater.clearElementInfos(updateElement, localElements);

                // error while updating, no need to go further...
                result = false;
                break;
            }
        }

        // some files hasn't be updated ?
        setState("Checking...", 60);

        if (!result)
        {
            System.err.println("Update processing has failed.");

            // delete update directory to restart update from scratch
            FileUtil.delete(Updater.UPDATE_DIRECTORY, true);

            // restore backup
            if (Updater.restore())
            {
                System.out.println("");
                System.out.println("Files correctly restored.");
                // delete backup directory as we don't need it anymore
                FileUtil.delete(Updater.BACKUP_DIRECTORY, true);
            }
            else
            {
                System.err.println("");
                System.err
                        .println("Some files cannot be restored, try to restore them manually from 'backup' directory.");
                System.err.println("If Icy doesn't start anymore you may need to reinstall the application.");
            }

            // validate elements
            Updater.validateElements(localElements);
            // and save them
            if (!Updater.saveElementsToXML(localElements, Updater.VERSION_NAME, false))
                System.err.println("Error while saving " + Updater.VERSION_NAME + " file.");

            // send report of the error
            report(strLog);
        }
        else
        {
            // delete obsolete files
            setState("Deleting obsoletes...", 60);
            Updater.deleteObsoletes();

            // cleanup
            setState("Cleaning...", 70);
            FileUtil.delete(Updater.UPDATE_DIRECTORY, true);
            FileUtil.delete(Updater.BACKUP_DIRECTORY, true);

            if (updateElements.size() == 0)
                System.out.println("Nothing to update.");
            else
            {
                // update XML version file
                setState("Updating XML...", 90);

                // validate elements (this actually remove obsoletes files)
                Updater.validateElements(localElements);

                if (!Updater.saveElementsToXML(localElements, Updater.VERSION_NAME, false))
                {
                    System.out.println("Error while saving " + Updater.VERSION_NAME + " file.");
                    System.out.println("The new version is correctly installed but version number informations");
                    System.out.println("will stay outdated until the next update.");
                }
                else
                    System.out.println("Update succefully completed.");
            }
        }

        if (result)
            setState("Succeed !", 100);
        else
            setState("Failed !", 100);

        return result;
    }

    private static String getVMParams()
    {
        // get JVM parameters stored in preferences
        final int maxMemory = ApplicationPreferences.getMaxMemoryMB();
        final int stackSize = ApplicationPreferences.getStackSizeKB();
        final String vmParams = ApplicationPreferences.getExtraVMParams();
        final String osVmParams = ApplicationPreferences.getOSExtraVMParams();

        String result = "";

        if (maxMemory != -1)
            result += " " + PARAM_MAX_MEMORY + maxMemory + "m";
        if (stackSize != -1)
            result += " " + PARAM_STACK_SIZE + stackSize + "k";
        if (!StringUtil.isEmpty(vmParams))
            result += " " + vmParams;
        if (!StringUtil.isEmpty(osVmParams))
            result += " " + osVmParams;

        return result;
    }

    private static String getAppParams()
    {
        // get app parameters stored in preferences
        return ApplicationPreferences.getAppParams();
    }

    public static boolean startICY(String directory)
    {
        setState("Launching Icy...", 0);
        if (frame != null)
            frame.setProgressVisible(false);

        // start icy
        final Process process = SystemUtil.execJAR(ICY_JARNAME, getVMParams(), getAppParams() + extraArgs, directory);

        // process not even created --> critical error
        if (process == null)
        {
            System.err.println("Can't launch execJAR(" + ICY_JARNAME + ", " + getVMParams() + ", " + getAppParams()
                    + extraArgs + "," + directory + ")");
            return false;
        }

        // wait a bit so it has sometime to compute
        ThreadUtil.sleep(1000);

        try
        {
            if (process.exitValue() != 0)
            {
                // get error output and redirect it
                final BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                try
                {
                    setState("Error while launching Icy", 0);
                    System.err.println("Can't launch execJAR(" + ICY_JARNAME + ", " + getVMParams() + ", "
                            + getAppParams() + extraArgs + "," + directory + ")");
                    System.err.println(stderr.readLine());
                    if (stderr.ready())
                        System.err.println(stderr.readLine());
                    System.out.println();
                    System.out.println("Trying to launch without specific parameters...");
                    System.out.println();
                }
                catch (Exception e)
                {
                    // ignore

                }

                return startICYSafeMode(directory);// , ICY_JARNAME);
            }
        }
        catch (IllegalThreadStateException e)
        {
            // thread not terminated, assume it's ok...
        }

        return true;
    }

    public static boolean startICYSafeMode(String directory)
    {
        setState("Launching Icy (safe mode)...", 0);
        if (frame != null)
            frame.setProgressVisible(false);

        // start icy in safe mode (no parameters)
        final Process process = SystemUtil.execJAR(ICY_JARNAME, "", "", directory);

        // process not even created --> critical error
        if (process == null)
        {
            System.err.println("Can't launch execJAR(" + ICY_JARNAME + ", \"\", \"\", " + directory + ")");
            System.out.println();
            System.out.println("Try to manually launch the following command :");
            System.out.println("java -jar updater.jar");
            return false;
        }

        // wait a bit so it has sometime to compute
        ThreadUtil.sleep(1000);

        try
        {
            if (process.exitValue() != 0)
            {
                // get error output and redirect it
                final BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                try
                {
                    setState("Error while launching Icy (safe mode)", 0);
                    System.err.println("Can't launch execJAR(" + ICY_JARNAME + ", \"\", \"\", " + directory + ")");
                    System.err.println(stderr.readLine());
                    if (stderr.ready())
                        System.err.println(stderr.readLine());

                    System.out.println();
                    System.out.println("Try to manually launch the following command :");
                    System.out.println("java -jar updater.jar");
                }
                catch (Exception e)
                {
                    // ignore

                }

                return false;
            }
        }
        catch (IllegalThreadStateException e)
        {
            // thread not terminated, assume it's ok...
        }

        return true;
    }

    private static void setState(final String state, final int progress)
    {
        ThreadUtil.invokeLater(new Runnable()
        {
            @Override
            public void run()
            {
                if (frame != null)
                {
                    frame.setTitle(state);
                    frame.setProgress(progress);
                }
            }
        });
    }

    private static boolean waitForLock(String lockName)
    {
        final long start = System.currentTimeMillis();
        final File f = new File(lockName);

        // may help
        if (!f.setWritable(true, false))
            f.setWritable(true, true);

        // ensure lock exist
        if (f.exists())
        {
            // wait while file is lock (we wait 15 seconds at max)
            while ((!f.canWrite()) && ((System.currentTimeMillis() - start) < (15 * 1000)))
            {
                System.gc();
                ThreadUtil.sleep(100);

                // may help
                if (!f.setWritable(true, false))
                    f.setWritable(true, true);
            }

            return f.canWrite();
        }

        return true;
    }

    /**
     * Report an error log to the Icy web site.
     * 
     * @param errorLog
     *        Error log to report.
     */
    private static void report(String errorLog)
    {
        final HashMap<String, String> values = new HashMap<String, String>();

        values.put(NetworkUtil.ID_PLUGINCLASSNAME, "");
        values.put(NetworkUtil.ID_ERRORLOG, "Updater version " + version + "\n\n" + errorLog);

        // send report
        NetworkUtil.report(values);
    }
}
