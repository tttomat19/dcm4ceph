package org.open_ortho.dcm4ceph.core;

import java.io.File;
import java.io.FileNotFoundException;


/**
 *
 * @author Toni Magni &lt;afm@case.edu&gt;
 *
 */
public class CephalogramTest {

    /**
     * @param args
     */
    public static void main(String[] args) throws FileNotFoundException {

        File cephfile1 = new File(args[0]);
        File cephfile2 = new File(args[1]);
        File fidfile = new File(args[2]);

        BBCephalogramSet cephSet = new BBCephalogramSet(cephfile1, cephfile2,
                fidfile);

        File rootdir = new File(cephfile1.getParent() + File.separator
                + "BBcephset");

        cephSet.writeCephs(rootdir);
        cephSet.writeDicomdir(rootdir);

        // printDicomElements(FileUtils.getDCMFile(cephfile));
    }

    // TODO make this class an aofficial testing class.

}
