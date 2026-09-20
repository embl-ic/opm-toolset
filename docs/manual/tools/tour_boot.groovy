/*
 * Loader for live_deskew_tour.groovy.
 *
 * Fiji shows a script's compile error in a window and then waits for it. With the desktop of
 * an unattended run that window is never seen, so the tour is compiled from here instead:
 * anything it throws, including a compile error, is appended to the tour report and the JVM is
 * halted. Set OPM_TOUR_SCRIPT to the tour; every other OPM_TOUR_* variable is read by the tour
 * itself.
 */
File report = new File(System.getenv('OPM_TOUR_REPORT') ?:
        new File(System.getProperty('java.io.tmpdir'), 'opm-tour-report.txt').path)
File script = new File(System.getenv('OPM_TOUR_SCRIPT') ?:
        new File(new File(System.getProperty('user.dir')), 'docs/manual/tools/live_deskew_tour.groovy').path)
try {
    new GroovyShell(this.class.classLoader, new Binding()).run(script, new String[0])
} catch (Throwable failure) {
    StringWriter trace = new StringWriter()
    failure.printStackTrace(new PrintWriter(trace))
    String message = 'TOUR FAILED: ' + trace
    System.out.println(message)
    report << '\n' + message + '\n'
}
Runtime.runtime.halt(0)
