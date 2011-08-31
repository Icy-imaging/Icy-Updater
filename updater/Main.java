/**
 * 
 */
package updater;

import icy.file.FileUtil;
import icy.preferences.GeneralPreferences;
import icy.preferences.IcyPreferences;
import icy.system.SystemUtil;
import icy.system.thread.ThreadUtil;
import icy.update.ElementDescriptor;
import icy.update.Updater;
import icy.util.StringUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * @author stephane
 */
public class Main
{
    // private static final String ICY_MAINCLASS = "icy.main.Icy";

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

    static UpdateFrame frame = null;
    static String extraArgs = "";

    /**
     * @param args
     *        Received from the command line.
     */
    public static void main(String[] args)
    {
        // try to detect if old ICY request update
        boolean oldVersion = false;
        boolean start = true;
        boolean update;

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

        // no error --> we can exit
        if (process(update, start))
        {
            frame.dispose();
            System.exit(0);
        }
        else
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
                frame = new UpdateFrame("ICY Updater");
                // update --> show progress frame immediately
                if (update)
                    frame.setVisible(true);
            }
        });

        // wait for lock
        if (!waitForLock(ICY_JARNAME))
        {
            System.err.println("Error : File " + ICY_JARNAME + " is locked !");
            System.err.println("Aborting...");
            return false;
        }

        boolean result;

        if (update)
        {
            if (!waitForLock(Updater.UPDATE_NAME))
            {
                System.err.println("Error : File " + Updater.UPDATE_NAME + " is locked !");
                System.err.println("Can't update, aborting...");
                return false;
            }

            result = doUpdate();
        }
        else
            result = true;

        // start ICY
        if (result && start)
            return startICY();

        return result;
    }

    public static boolean doUpdate()
    {
        final ArrayList<ElementDescriptor> localElements = Updater.getLocalElements();
        final ArrayList<ElementDescriptor> updateElements = Updater.getUpdateList();
        boolean result = true;

        // get list of element to update
        for (ElementDescriptor updateElement : updateElements)
        {
            final int progress = (updateElements.indexOf(updateElement) * 60) / updateElements.size();
            final String updateElementName = updateElement.getName();

            // can't update updater here (it should be already updated)
            if (updateElementName.equals(Updater.ICYUPDATER_NAME))
                continue;

            setState("Updating : " + updateElementName, progress);

            // find corresponding current local element
            final ElementDescriptor localElement = Updater.findElement(updateElementName, localElements);

            // move files (ICY Updater already updated)
            if (Updater.updateFiles(updateElement.getFiles()))
            {
                // local element doesn't exist
                if (localElement == null)
                    // add it
                    localElements.add(updateElement);
                else
                {
                    // remove old local element
                    localElements.remove(localElement);
                    // and replace it by new updated element
                    localElements.add(updateElement);
                }
            }
            else
            {
                // error while updating, no need to go further...
                result = false;
                break;
            }
        }

        // some files hasn't be udpated ?
        setState("Checking...", 60);
        if (!result)
        {
            System.err.println("Update processing has failed.");

            // delete update directory to restart update from scratch
            FileUtil.delete(Updater.UPDATE_DIRECTORY, true);

            // restore backup
            if (Updater.restore())
            {
                System.out.println("Files correctly restored.");
                // delete backup directory as we don't need it anymore
                FileUtil.delete(Updater.BACKUP_DIRECTORY, true);
            }
            else
                System.err.println("Can't restore all files.");
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

            // update XML version file
            setState("Updating XML...", 90);
            if (!Updater.saveElementsToXML(localElements, Updater.VERSION_NAME, false))
            {
                System.err.println("Error while updating " + Updater.VERSION_NAME + " file.");
                result = false;
            }
            else if (updateElements.size() == 0)
                System.out.println("Nothing to update.");
            else
                System.out.println("Update succefully completed.");
        }

        if (result)
            setState("Success !", 100);
        else
            setState("Failed !", 100);

        return result;
    }

    private static String getVMParams()
    {
        // get JVM parameters stored in preferences
        final int maxMemory = GeneralPreferences.getMaxMemoryMB();
        final int stackSize = GeneralPreferences.getStackSizeKB();
        final String vmParams = GeneralPreferences.getExtraVMParams();
        final String osVmParams = GeneralPreferences.getOSExtraVMParams();

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
        return GeneralPreferences.getAppParams();
    }

    public static boolean startICY()
    {
        setState("Launching ICY...", 0);
        frame.setProgressVisible(false);

        // load preferences
        IcyPreferences.init();
        // start icy
        final Process process = SystemUtil.execJAR(ICY_JARNAME, getVMParams(), getAppParams() + extraArgs);

        // process not even created --> critical error
        if (process == null)
        {
            System.err.println("Can't launch execJAR(" + ICY_JARNAME + ", " + getVMParams() + ", " + getAppParams()
                    + ", " + extraArgs + ")");
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
                    setState("Error while launching ICY", 0);
                    System.err.println("Error while launching execJAR(" + ICY_JARNAME + ", " + getVMParams() + ", "
                            + getAppParams() + ", " + extraArgs + ")");
                    System.err.println(stderr.readLine());
                    if (stderr.ready())
                        System.err.println(stderr.readLine());
                    System.err.println("Trying to launch without specific parameters...");
                }
                catch (Exception e)
                {
                    // ignore

                }

                return startICYSafeMode();
            }
        }
        catch (IllegalThreadStateException e)
        {
            // thread not terminated, assume it's ok...
        }

        return true;
    }

    public static boolean startICYSafeMode()
    {
        setState("Launching ICY (safe mode)...", 0);
        frame.setProgressVisible(false);

        // start icy in safe mode (no parameters)
        final Process process = SystemUtil.execJAR(ICY_JARNAME);

        // process not even created --> critical error
        if (process == null)
        {
            System.err.println("Can't launch execJAR(" + ICY_JARNAME + ")");
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
                    setState("Error while launching ICY (safe mode)", 0);
                    System.err.println("Error while launching execJAR(" + ICY_JARNAME + ")");
                    System.err.println(stderr.readLine());
                    if (stderr.ready())
                        System.err.println(stderr.readLine());

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
                frame.setTitle(state);
                frame.setProgress(progress);
            }
        });
    }

    private static boolean waitForLock(String lockName)
    {
        final long start = System.currentTimeMillis();
        final File f = new File(lockName);

        // ensure lock exist
        if (f.exists())
        {
            // wait while file is lock (we wait 15 seconds at max)
            while ((!f.canWrite()) && ((System.currentTimeMillis() - start) < (15 * 1000)))
                ThreadUtil.sleep(100);

            return f.canWrite();
        }

        return true;
    }
}
