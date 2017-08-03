package com.busybox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class Busybox {
    public static final Worker SU=new Worker("su");
    public static final Worker SH=new Worker("sh");
    private static String path;
    private static int activitiesCount;

    /**
     * Executes shell commands
     *
     * @param commands commands to execute
     * @return lines: result of execution
     * @throws IOException exception with error text of commands execution
     */
    public static String[] execute(String... commands) throws IOException{
        if(SU.isAvailable())return SU.execute(commands);
        else return SH.execute(commands);
    }

    /**
     * Same as {@link #execute(String...) execute(String...)} but throws no exception
     *
     * @param commands lines: result of execution
     * @return lines: result of execution or null if execution failed
     */
    public static String[] executeSafe(String... commands) {
        try {
            return execute(commands);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Kill all active workers and clear workers pool
     */
    public static void reset(){
        SU.reset();
        SH.reset();
    }

    /**
     * Initialize and extract busybox binary executable
     * @param context app context
     * @throws IOException if initialization failed
     */
    public static void init(Application context) throws IOException{
        File file=new File(context.getFilesDir(),"busybox");
        if(!file.exists()){
            InputStream inputStream=context.getAssets().open("busybox");
            OutputStream outputStream=new FileOutputStream(file);
            IOUtils.copy(inputStream,outputStream);
            inputStream.close();
            outputStream.close();
        }
        if(!file.canExecute()&&!file.setExecutable(true))
            throw new IOException(String.format("Can't set executable %s",file.getAbsolutePath()));
        path=file.getAbsolutePath().replaceAll("\\s","\\ ");
        context.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {}

            @Override
            public void onActivityCreated(Activity activity, Bundle bundle) {
                activitiesCount++;
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if(--activitiesCount<=0)reset();
            }
        });
    }

    public static class Worker{
        private final String shell;
        private final Map<Process,Boolean>pool=new HashMap<>();

        private Worker(String shell) {
            this.shell = shell;
        }

        private String makeMarker(){
            return String.format(Locale.ROOT,"marker{%d}",System.nanoTime());
        }

        private Process getProcess() throws IOException{
            for(Map.Entry<Process,Boolean>entry:pool.entrySet()){
                if(!entry.getValue())return entry.getKey();
            }
            Process process=Runtime.getRuntime().exec(shell);
            pool.put(process,false);
            return process;
        }

        /**
         * See {@link Busybox#reset() Busybox.reset()}
         */
        public void reset(){
            for(Process process:pool.keySet())process.destroy();
            pool.clear();
        }

        /**
         * @return true if busybox can execute commands with provided shell
         */
        public boolean isAvailable(){
            try{
                return execute("echo test")[0].equals("test");
            }catch (Exception e){
                return false;
            }
        }

        private String getAvailableText(InputStream inputStream)throws IOException{
            int available;
            StringBuilder builder=new StringBuilder();
            while ((available=inputStream.available())>0){
                byte[] buffer=new byte[available];
                //noinspection ResultOfMethodCallIgnored
                inputStream.read(buffer);
                builder.append(new String(buffer));
            }
            return builder.toString();
        }

        private String makeCommand(String... commands){
            StringBuilder builder=new StringBuilder();
            for(String command:commands)
                builder.append(path).append(" ").append(command).append("\n");
            return builder.toString();
        }

        public String[] executeSafe(String... commands) {
            try {
                return execute(commands);
            } catch (IOException e) {
                return null;
            }
        }

        /**
         * See {@link Busybox#execute(String...) Busybox.execute(String...)}
         */
        public String[] execute(String... commands) throws IOException {
            if(path==null)throw new IOException("Busybox is not initialized");
            if(commands.length==0)return new String[0];
            Process process=getProcess();
            pool.put(process,true);
            InputStream inputStream=process.getInputStream();
            OutputStream outputStream=process.getOutputStream();
            InputStream errorStream=process.getErrorStream();
            final String marker=makeMarker();
            outputStream.write(makeCommand(commands).getBytes());
            outputStream.write(String.format("echo %s\n",marker).getBytes());
            outputStream.flush();
            String result="";
            String error="";
            while (!result.trim().endsWith(marker) && !error.endsWith("\n")) {
                result += getAvailableText(inputStream);
                error += getAvailableText(errorStream);
            }
            pool.put(process,false);
            error=error.trim();
            if(!error.isEmpty())throw new IOException(error);
            result=result.trim();
            result=result.substring(0,result.lastIndexOf(marker)).trim();
            if(result.isEmpty())return new String[0];
            else if(!result.contains("\n"))return new String[]{result};
            else return result.split("\n");
        }
    }
}
