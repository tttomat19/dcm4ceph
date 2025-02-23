/**
 * dcm4ceph, a DICOM library for digital cephalograms
 * Copyright (C) 2006  Toni Magni
 *
 * Toni Magni
 * email: afm@case.edu
 * website: https://github.com/open-ortho/dcm4ceph
 *
 */

package org.open_ortho.dcm4ceph.core;

import org.dcm4che2.data.BasicDicomObject;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.UID;
import org.dcm4che2.data.VR;
import org.dcm4che2.io.DicomOutputStream;
import org.dcm4che2.iod.composite.DXImage;
import org.dcm4che2.iod.module.macro.AnatomicRegionCode;
import org.dcm4che2.iod.module.macro.Code;
import org.dcm4che2.iod.module.macro.ImageSOPInstanceReferenceAndPurpose;
import org.dcm4che2.iod.module.macro.PatientOrientationCode;
import org.dcm4che2.iod.module.macro.SOPInstanceReferenceAndPurpose;
import org.dcm4che2.iod.module.macro.ViewCode;
import org.dcm4che2.iod.validation.ValidationContext;
import org.dcm4che2.iod.validation.ValidationResult;
import org.dcm4che2.iod.value.ImageLaterality;
import org.dcm4che2.iod.value.ImageOrientationPatient;
import org.dcm4che2.iod.value.ImageTypeValue1;
import org.dcm4che2.iod.value.ImageTypeValue2;
import org.dcm4che2.iod.value.ImageTypeValue3;
import org.dcm4che2.iod.value.Modality;
import org.dcm4che2.iod.value.PatientOrientation;
import org.dcm4che2.iod.value.PhotometricInterpretation;
import org.dcm4che2.iod.value.PixelRepresentation;
import org.dcm4che2.iod.value.PositionerType;
import org.dcm4che2.iod.value.PresentationIntentType;
import org.dcm4che2.iod.value.TableType;
import org.dcm4che2.util.UIDUtils;
import org.devlib.schmidt.imageinfo.ImageInfo;
import org.open_ortho.dcm4ceph.util.DcmUtils;
import org.open_ortho.dcm4ceph.util.FileUtils;
import org.open_ortho.dcm4ceph.util.Log;

import javax.imageio.stream.FileImageInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

/**
 * This class represents a digital cephalogram.
 *
 * @author afm
 *
 */
public class Cephalogram extends DXImage {

    /**
     * Latin alphabet No. 1
     */
    private static final String DEFAULT_CHARSET = "ISO_IR 100";

    private static final String transferSyntax = UID.JPEGBaseline1;

    private static final double mmPerInch = 25.4; // 25.4 mm to an inch.

    private String patientOrientation;

    private static final int minimumAllowedDPI = 128;

    // private int DPI = 300;

    /**
     * input file reference
     */
    private File imageFile;

    /**
     * input properties
     */
    private Properties instanceProperties;

    public static final String[] PRIMARYIMAGETYPE = { ImageTypeValue1.ORIGINAL,
            ImageTypeValue2.PRIMARY, ImageTypeValue3.NULL };

    public static final String[] SECONDARYIMAGETYPE = {
            ImageTypeValue1.ORIGINAL, ImageTypeValue2.SECONDARY,
            ImageTypeValue3.NULL };

    public Cephalogram(File cephFile) throws FileNotFoundException {
        this(cephFile, null);
    }

    public Cephalogram(File cephFile, File configFile) throws FileNotFoundException {
        super(new BasicDicomObject());

        if (!cephFile.exists()) {
            throw new FileNotFoundException("input file not found");
        }
        setImageFile(cephFile);

        initDximage();

        // if explicit configFile .properties file is passed, use that first
        if (configFile == null) {
            // if no configFile is passed, try to load default
            // use .properties file with same name as image, but swapped extension
            // the .properties file existence is mandatory
            configFile = FileUtils.getPropertiesFile(cephFile);
        }
        Properties configLoaded = FileUtils.loadProperties(configFile);
        if (configLoaded == null) {
            Log.err(
            "Cannot read from file " + configFile.toPath() + ".\n" +
                "Please use 2 files as input: %name%.jpg and %name%.properties .\n" +
                "The .properties file is mandatory.\n" +
                "You may find example .properties file here: https://github.com/open-ortho/dcm4ceph/blob/master/dcm4ceph-sampledata/B1893F12.properties \n" +
                "You may also find sensible defaults .properties file here: https://github.com/open-ortho/dcm4ceph/blob/master/dcm4ceph-core/src/main/resources/ceph_defaults.properties "
            );
            // exit if we were unable to load properties
            System.exit(1);
            return;
        }
        instanceProperties = configLoaded;
    }

    Cephalogram(DicomObject dcmobj) {
        super(dcmobj);
    }

    /**
     * Perform initialization procedure.
     * <p>
     * This method sets the various attributes to values which are independent
     * the instance of the class.
     */
    public void initDximage() {
        super.init();

        // Set SOP stuff.
        getSopCommonModule().setSOPClassUID(
                UID.DigitalXRayImageStorageForProcessing);
        getSopCommonModule().setSOPInstanceUID(UIDUtils.createUID());

        // Set the Series (DX and General) Module Attributes
        getDXSeriesModule().setModality(Modality.DX);
        if (this.getSeriesUID() == null) {
            this.setSeriesUID(makeInstanceUID());
        }

        // Set a default series date of now, which will be changed later.
        getDXSeriesModule().setSeriesDateTime(new Date());
        getDXSeriesModule().setPresentationIntentType(
                PresentationIntentType.PROCESSING);

        // Set the Image Module attributes
        getDXImageModule().setSamplesPerPixel(1);

        setPrimaryImageType();

        // Set Positioner type to CEPHALOSTAT
        getDXPositioningModule().setPositionerType(PositionerType.CEPHALOSTAT);

        // Set Patient Orientation Code (0054,0410) to ERECT.
        PatientOrientationCode pxOrientation = (PatientOrientationCode) setCode(
                new PatientOrientationCode(), "F-10440", "ERECT", "SNM3");
        getDXPositioningModule().setPatientOrientationCode(pxOrientation);

        // Set samples per pixel according to grayscale

        // Set Table Type (0018,113A) to "FIXED"
        getDXPositioningModule().setTableType(TableType.FIXED);

        // Set DX Abatomy Imaged Module
        // TODO verify that this image laterality is set correctly.
        getDXAnatomyImageModule().setImageLaterality(ImageLaterality.UNPAIRED);
        AnatomicRegionCode anatomicCode = (AnatomicRegionCode) setCode(
                new AnatomicRegionCode(), "T-D1100", "Head, NOS", "SNM3");
        getDXAnatomyImageModule().setAnatomicRegionCode(anatomicCode);
    }

    /**
     * Set this cephalogram to ORIGINAL/PRIMARY.
     * <p>
     * This is the default
     *
     *
     */
    public void setPrimaryImageType() {
        getDXImageModule().setImageType(PRIMARYIMAGETYPE);
    }

    /**
     * Set this cephalogram to ORIGINAL/SECONDARY.
     * <p>
     * Use this when the cephalogram does not come directly from the source.
     *
     */
    public void setSecondaryImageType() {
        getDXImageModule().setImageType(SECONDARYIMAGETYPE);
        // TODO find out if scanned cephs are considered secondary.
    }

    /**
     * Prepare object for writing.
     * <p>
     * This method sets the various DICOM attributes that are specific to this
     * Cephalogram instance.
     *
     * @see #setDcmobjTagsFromProperties(Properties)
     * @see #setImageAttributes(FileImageInputStream)
     *
     */
    private void prepareDcmobj() throws IOException {
        setDcmobjTagsFromProperties(instanceProperties);
        try (FileImageInputStream is = new FileImageInputStream(imageFile)) {
            setImageAttributes(is);
        }
        DcmUtils.ensureUID(dcmobj, Tag.StudyInstanceUID);
        DcmUtils.ensureUID(dcmobj, Tag.SeriesInstanceUID);
        DcmUtils.ensureUID(dcmobj, Tag.SOPInstanceUID);

        dcmobj.putString(Tag.SpecificCharacterSet, VR.CS, DEFAULT_CHARSET);
        dcmobj.initFileMetaInformation(transferSyntax);
    }

    public void validate(ValidationContext ctx, ValidationResult result) {
        super.validate(ctx, result);
        // BasicDicomObject testobj = new BasicDicomObject();

        if (getDXImageModule().getBitsAllocated() < 16) {
            result.logInvalidValue(Tag.BitsAllocated, dcmobj);
        }
        if (getDXImageModule().getBitsStored() < 12) {
            result.logInvalidValue(Tag.BitsStored, dcmobj);
        }
        if (!validatePixelSpacing()) {
            result.logInvalidValue(Tag.PixelSpacing, dcmobj);
        }

        if (getDXImageModule().getRedPaletteColorLookupTableDescriptor() != null) {
            getDXImageModule().setRedPaletteColorLookupTableDescriptor(null);
        }
        if (getDXImageModule().getRedPaletteColorLookupTableData() != null) {
            getDXImageModule().setRedPaletteColorLookupTableData(null);
        }

        if (getDXImageModule().getGreenPaletteColorLookupTableDescriptor() != null) {
            getDXImageModule().setGreenPaletteColorLookupTableDescriptor(null);
        }
        if (getDXImageModule().getGreenPaletteColorLookupTableData() != null) {
            getDXImageModule().setGreenPaletteColorLookupTableData(null);
        }

        if (getDXImageModule().getBluePaletteColorLookupTableDescriptor() != null) {
            getDXImageModule().setBluePaletteColorLookupTableDescriptor(null);
        }
        if (getDXImageModule().getBluePaletteColorLookupTableData() != null) {
            getDXImageModule().setBluePaletteColorLookupTableData(null);
        }

    }

    public void setDcmobjTagsFromProperties(Properties cephprops) {
        getPatientModule()
                .setPatientName(cephprops.getProperty("patientName"));
        getPatientModule().setPatientID(cephprops.getProperty("patientID"));
        try {
            DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
            Date PatientBirthDate = formatter.parse(cephprops.getProperty("patientDOB"));
            getPatientModule().setPatientBirthDate(PatientBirthDate);
        } catch (ParseException e) {
            e.printStackTrace();
            Log.warn("Could not parse DOB correctly. Setting to null.");
            getPatientModule().setPatientBirthDate(null);
        }

        try {
            DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            getGeneralStudyModule().setStudyDateTime(
                    formatter.parse(
                            cephprops.getProperty("studyDate") + " "
                                    + cephprops.getProperty("studyTime")));
        } catch (ParseException e) {
            e.printStackTrace();
            Log.warn("Could not parse Study Date Time correctly. Using current date time.");
            getGeneralStudyModule().setStudyDateTime(new Date());
        }
        // Set Series date and time to Study date and time.
        getDXSeriesModule().setSeriesDateTime(getGeneralStudyModule().getStudyDateTime());

        getPatientModule().setEthnicGroup(cephprops.getProperty("ethnicGroup"));

        getDicomObject().putString(Tag.PatientAge,VR.AS,cephprops.getProperty("patientAge"));
        getPatientModule().setPatientSex(cephprops.getProperty("patientSex"));

        getGeneralStudyModule().setReferringPhysiciansName(
                cephprops.getProperty("referringPhysician"));
        getGeneralStudyModule().setStudyID(cephprops.getProperty("studyID"));
        getGeneralStudyModule().setAccessionNumber(
                cephprops.getProperty("accessionNumber"));

        getDXSeriesModule().setSeriesNumber(
                cephprops.getProperty("seriesNumber"));

        getDXImageModule().setInstanceNumber(
                cephprops.getProperty("instanceNumber"));

        String[] patientOrientation = {
                cephprops.getProperty("patientOrientationRow"),
                cephprops.getProperty("patientOrientationColumn") };
        getDXImageModule().setPatientOrientation(patientOrientation);
        setImageOrientation(patientOrientation);

        getDXSeriesModule().setSeriesNumber(
                cephprops.getProperty("seriesNumber"));

        try {
            setDistances(
                      Float.parseFloat(cephprops.getProperty("sid"))
                    , Float.parseFloat(cephprops.getProperty("sod")) );
        } catch (NumberFormatException e) {
            e.printStackTrace();
            Log.warn("Could not parse sid and sod. Please set proper sid= and sod= as decimal.");
        }

        setMagnification(cephprops.getProperty("mag"));

        if ("PA".equals(cephprops.getProperty("cephalogramType"))) {
            setPosteroAnterior();
        }
        if ("L".equals(cephprops.getProperty("cephalogramType"))) {
            setLeftLateral();
        }
    }

    private void setImageOrientation(String[] patientOrientation2) {
        if (Arrays.equals(patientOrientation2, PatientOrientation.AF)) {
            dcmobj.putFloats(Tag.ImageOrientationPatient, VR.DS,
                    ImageOrientationPatient.AF);
        } else if (Arrays.equals(patientOrientation2, PatientOrientation.PF)) {
            dcmobj.putFloats(Tag.ImageOrientationPatient, VR.DS,
                    ImageOrientationPatient.PF);
        } else if (Arrays.equals(patientOrientation2, PatientOrientation.LF)) {
            dcmobj.putFloats(Tag.ImageOrientationPatient, VR.DS,
                    ImageOrientationPatient.LF);
        } else if (Arrays.equals(patientOrientation2, PatientOrientation.RF)) {
            dcmobj.putFloats(Tag.ImageOrientationPatient, VR.DS,
                    ImageOrientationPatient.RF);
        } else if (Arrays.equals(patientOrientation2, PatientOrientation.FP)) {
            dcmobj.putFloats(Tag.ImageOrientationPatient, VR.DS,
                    ImageOrientationPatient.FP);
        } else {
            System.err.println("Cannot set image orientation correctly");
        }
    }

    /**
     * Instance Number of Study Module
     * <p>
     * This is the identifier that uniquely identifies the Study this
     * cephalogram is part of.
     *
     * @return Returns the instanceNumber.
     */
    public String getStudyUID() {
        return getGeneralStudyModule().getStudyInstanceUID();
    }

    /**
     * Instance Number of Study Module
     * <p>
     * This is the identifier that uniquely identifies the Study this
     * cephalogram is part of.
     *
     * @param uid
     *                       The instanceNumber to set.
     */
    public void setStudyUID(String uid) {
        getGeneralStudyModule().setStudyInstanceUID(uid);
    }

    /**
     * Descritpion of the study.
     *
     * @return Returns the description.
     */
    public String getStudyDescription() {
        return getGeneralStudyModule().getStudyDescription();
    }

    /**
     * Descritpion of the study.
     *
     * @param description study description
     */
    public void setStudyDescription(String description) {
        getGeneralStudyModule().setStudyDescription(description);
    }

    /**
     * Instance Number of Series Module
     * <p>
     * This is the identifier that uniquely identifies the Series this
     * cephalogram is part of.
     *
     * @return Returns the instanceNumber.
     */
    public String getSeriesUID() {
        return getDXSeriesModule().getSeriesInstanceUID();
    }

    /**
     * Instance Number of Image Module
     * <p>
     * This is the identifier that uniquely identifies the Series this
     * cephalogram is part of.
     *
     * @param instanceNumber
     *                       The instanceNumber to set.
     */
    public void setSeriesUID(String instanceNumber) {
        getDXSeriesModule().setSeriesInstanceUID(instanceNumber);
    }

    /**
     * Get the unique identifier.
     * <p>
     * This is the identifier that uniquely identifies this image.
     *
     * @return SOPInstanceUID tag value
     */
    public String getUID() {
        return getSopCommonModule().getSOPInstanceUID();
    }

    /**
     * Orientation of patient with respect to detector.
     * <p>
     * This field is supposed to make the correct letters show up, which help
     * orient the examiner understand how the patient is oriented when looking
     * at an image
     *
     * @return Returns the patientOrientation.
     */
    public String getPatientOrientation() {
        return patientOrientation;
    }

    /**
     * Orientation of patient with respect to detector.
     * <p>
     * This field is supposed to make the correct letters show up, which help
     * orient the examiner understand how the patient is oriented when looking
     * at an image
     *
     * @param patientOrientation
     *                           The patientOrientation to set.
     */
    public void setPatientOrientation(String patientOrientation) {
        this.patientOrientation = patientOrientation;
    }

    // /**
    // * Gets the image resolution.
    // * <p>
    // * Default resolution is 300 dpi.
    // *
    // * @return The DPI of the image.
    // */
    // public int getResolution() {
    // return DPI;
    // }

    // /**
    // * Sets the image resolution in DPI.
    // * <p>
    // * Default resolution is 300 dpi.
    // *
    // * @param dpi
    // */
    // public void setResolution(int dpi) {
    // DPI = dpi;
    // }
    //
    /**
     * @param file The imageFile to set.
     */
    public void setImageFile(File file) {
        this.imageFile = file;
    }

    /**
     * @return Returns the imageFile.
     */
    public File getImageFile() {
        return imageFile;
    }

    /**
     * Gets the pure file name of the DICOM representation of this Cephalogram.
     *
     * @return image filename with output .dcm format extension
     */
    public String getDCMFileName() {
        return FileUtils.getDCMFileName(imageFile);
    }

    private boolean validatePixelSpacing() {
        boolean invalid = false;
        float[] pixelspacing = getDXDetectorModule().getPixelSpacing();
        for (float v : pixelspacing) {
            if (v > getMaximumPixelSpacing()) {
                invalid = true;
            }
        }
        return !invalid;

    }

    /**
     * Get the maxmimum allowed resolution value.
     * <p>
     * This is a value in mm. Any values greater than this is not allowed, as it
     * is considered to be a sufficient resolution for accurate measurments.
     *
     * @return The resolution in distance between one pixel and the next.
     */
    public float getMaximumPixelSpacing() {
        return (float) (1.0 / minimumAllowedDPI * mmPerInch);
    }

    /**
     * Set radiographic magnification.
     *
     * @param mags
     *            Magnification in percentage.
     */
    public void setMagnification(String mags) {
        if ((mags != null) && (!mags.equals(""))) {
            setMagnification(Float.parseFloat(mags));
        }
    }

    private void setMagnification(float mag) {
        mag /= 100;
        getDXPositioningModule().setEstimatedRadiographicMagnificationFactor(
                mag);
    }

    private void setDistances(float SID, float SOD) {
        getDXPositioningModule().setDistanceSourceToDetector(SID);
        getDXPositioningModule().setDistanceSourceToPatient(SOD);

        setMagnification(SID / SOD);
    }

    /**
     * Sets patient to detector and patient to x-ray source distances.
     *
     * Distances are measeured from the midsagittal plane for lateral cephs, and
     * transmeatal axis (ear rods) for PA cephs. Detector is either the film or
     * the digitizing detector, in the case of digital x-ray machines.
     *
     * @param SID
     *            Source to Detector distance in mm.
     * @param SOD
     *            X-ray source to Patient distance in mm.
     */
    public void setDistance(String SID, String SOD) {
        if (SID != null && SOD != null) {
            setDistances(Float.parseFloat(SID), Float.parseFloat(SOD));
        }
    }

    /**
     * A shortcut for standard postero-anterior cephalograms.
     *
     * Sets:
     * <ul>
     * <li>Primary Angle: 180
     * <li>Secondary Angle: 0
     * <li>View Code: SNM3 R-10214 postero-anterior
     * </ul>
     *
     */
    public void setPosteroAnterior() {
        setOrientation(180, 0, (ViewCode) setCode(new ViewCode(), "R-10214",
                "postero-anterior", "SNM3"));
        getDXSeriesModule()
                .setSeriesDescription("POSTERO-ANTERIOR CEPHALOGRAM");

    }

    /**
     * A shortcut for standard antero-posterior cephalograms.
     *
     * Sets:
     * <ul>
     * <li>Primary Angle: 0
     * <li>Secondary Angle: 0
     * <li>View Code: SNM3 R-10206 antero-posterior
     * </ul>
     *
     */
    public void setAnteroPosterior() {
        setOrientation(0, 0, (ViewCode) setCode(new ViewCode(), "R-10206",
                "antero-posterior", "SNM3"));
    }

    /**
     * A shortcut for standard right-lateral cephalograms.
     *
     * Sets:
     * <ul>
     * <li>Primary Angle: -90
     * <li>Secondary Angle: 0
     * <li>View Code: SNM3 R-10232 right lateral
     * </ul>
     *
     */
    public void setRightLateral() {
        setOrientation(-90, 0, (ViewCode) setCode(new ViewCode(), "R-10232",
                "right lateral", "SNM3"));
    }

    /**
     * A shortcut for standard left-lateral cephalograms.
     *
     * <ul>
     * <li>Primary Angle: +90
     * <li>Secondary Angle: 0
     * <li>View Code: SNM3 R-10236 left lateral
     * </ul>
     *
     */
    public void setLeftLateral() {
        setOrientation(90, 0, (ViewCode) setCode(new ViewCode(), "R-10236",
                "left lateral", "SNM3"));
        getDXSeriesModule().setSeriesDescription("LATERAL CEPHALOGRAM");

    }

    /**
     * Indicates whether or not image contains sufficient burned in annotation
     * to identify the patient and date the images was acquired.
     * <p>
     * Type 3
     *
     * @param annotations burnedInAnnotation yes or no
     */
    public void setBurnedinAnnotations(boolean annotations) {
        if (annotations) {
            getDXImageModule().setBurnedInAnnotation("YES");
        } else {
            getDXImageModule().setBurnedInAnnotation("NO");
        }
    }

    /**
     * Reference other image of a lateral/pa ceph pair.
     *
     * @param uid
     *            The instance UID of a DX IOD image for processing.
     */
    public void setReferencedImage(String uid) {
        ImageSOPInstanceReferenceAndPurpose[] imagesops = new ImageSOPInstanceReferenceAndPurpose[1];
        imagesops[0] = new ImageSOPInstanceReferenceAndPurpose();

        imagesops[0]
                .setReferencedSOPClassUID(UID.DigitalXRayImageStorageForProcessing);
        imagesops[0].setReferencedSOPInstanceUID(uid);
        imagesops[0].setPurposeOfReferenceCode(makeReferencedImageCode());

        getDXImageModule().setReferencedImages(imagesops);
    }

    /**
     * Write this Cephalogram in a DICOM .dcm file.
     * <p>
     * Before writing, checks the validity of the object. The output file will
     * have the same name as the input image file of this Cephalogram, with its
     * extension replaced with .dcm and in the same folder.
     *
     * @return The {@link File} this object was written to, or null if the
     *         object was not written because of its invalidiy
     *
     * @see #validate(ValidationContext, ValidationResult)
     *
     */
    public File writeDCM() throws IOException {
        return writeDCM(getDCMFile());
    }

    /**
     * Write this Cephalogram in a DICOM .dcm file.
     * <p>
     *
     * @param path
     *                 The new directory where to store the filename.
     * @param filename
     *                 The new filename of the file. Can be {@code null} in which
     *                 case the the value returne by {@link #getDCMFileName()} will
     *                 be used.
     * @return The {@link File} this object was written to, or null if the
     *         object was not written because of its invalidiy
     */
    public File writeDCM(String path, String filename) throws IOException {
        if (filename == null) {
            filename = getDCMFileName();
        }
        return writeDCM(new File(path + File.separator + filename));
    }

    /**
     * Write this Cephalogram in a DICOM .dcm file.
     * <p>
     * Before writing, checks the validity of the object.
     *
     *
     * @param dcmFile
     *                The output file.
     *
     * @return The {@link File} this object was written to, or null if the
     *         object was not written because of its invalidiy
     *
     * @see #validate(ValidationContext, ValidationResult)
     *
     */
    public File writeDCM(File dcmFile) throws IOException {
        if (dcmFile == null) {
            return writeDCM();
        }

        // First prepare the dicom object.
        prepareDcmobj();

        // Then verify it.
        ValidationResult results = new ValidationResult();
        validate(new ValidationContext(), results);

        if (!results.isValid()) {
            Log.err("Dicom object did not pass validity tests.");
            System.err.println(results.getInvalidValues().toString());
        }

        try (FileOutputStream fos = new FileOutputStream(dcmFile);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            DicomOutputStream dos = new DicomOutputStream(bos);
            FileImageInputStream instream = new FileImageInputStream(imageFile);
        ) {
            Log.info("Writing to file " + dcmFile.getCanonicalPath());

            dos.writeDicomFile(dcmobj);
            dos.writeHeader(Tag.PixelData, VR.OB, -1);
            dos.writeHeader(Tag.Item, null, 0);
            int jpgLen = (int) instream.length();
            dos.writeHeader(Tag.Item, null, (jpgLen + 1) & ~1);
            int bufferSize = 8192;
            byte[] b = new byte[bufferSize];
            int r;
            while ((r = instream.read(b)) > 0) {
                dos.write(b, 0, r);
            }
            if ((jpgLen & 1) != 0) {
                dos.write(0);
            }
            dos.writeHeader(Tag.SequenceDelimitationItem, null, 0);
        }
        return dcmFile;
    }

    public File getDCMFile() {
        return FileUtils.getDCMFile(this.imageFile);
    }

    /**
     * Makes the code for referencing other cephalogram image.
     * <p>
     * This method generates the appopriate code that should be used when
     * referencing the other cephalogram of a lateral/pa pair.
     *
     * @return
     */
    private Code makeReferencedImageCode() {
        Code c = new Code();
        c.setCodeMeaning("Other image of biplane pair");
        c.setCodeValue("121314");
        c.setCodingSchemeDesignator("DCM");

        return c;
    }

    public void setReferencedFiducialSet(String uid) {
        SOPInstanceReferenceAndPurpose[] fidsops = new SOPInstanceReferenceAndPurpose[1];
        fidsops[0] = new SOPInstanceReferenceAndPurpose();

        fidsops[0].setReferencedSOPClassUID(UID.SpatialFiducialsStorage);
        fidsops[0].setReferencedSOPInstanceUID(uid);
        fidsops[0].setPurposeOfReferenceCode(makeReferencedFiducialCode());

        getDXImageModule().setReferencedInstances(fidsops);
    }

    /**
     * @return Returns the sbFiducialSet.
     */
    public String getReferencedFiducialSet() {
        return getDXImageModule().getReferencedInstances()[0]
                .getReferencedSOPInstanceUID();
    }

    private Code makeReferencedFiducialCode() {
        Code c = new Code();
        c.setCodeMeaning("Fiducial mark");
        c.setCodeValue("112171");
        c.setCodingSchemeDesignator("DCM");
        c.setCodingSchemeVersion("01");

        return c;
    }

    private void setImageAttributes(FileImageInputStream fiis)
            throws IOException {

        ImageInfo ii = new ImageInfo();
        ii.setInput(fiis);
        ii.setDetermineImageNumber(true); // default is false
        ii.setCollectComments(true); // default is false
        if (!ii.check()) {
            Log.err("Not a supported image file format.");
            return;
        }
        Log.info(ii.getFormatName() + ", " + ii.getMimeType() + ", "
                + ii.getWidth() + " x " + ii.getHeight() + " pixels, "
                + ii.getBitsPerPixel() + " bits per pixel, "
                + ii.getPhysicalHeightDpi() + "x" + ii.getPhysicalWidthDpi()
                + " DPI, " + ii.getPhysicalHeightInch() + "x"
                + ii.getPhysicalWidthInch() + " inch.");

        getDXImageModule().setRows(ii.getHeight());
        getDXImageModule().setColumns(ii.getWidth());

        getDXImageModule().setSamplesPerPixel(1);
        getDXImageModule().setPhotometricInterpretation(
                PhotometricInterpretation.MONOCHROME2);

        getDXImageModule().setBitsAllocated(ii.getBitsPerPixel());
        getDXImageModule().setBitsStored(ii.getBitsPerPixel());
        getDXImageModule().setHighBit(ii.getBitsPerPixel() - 1);

        getDXImageModule().setPixelRepresentation(PixelRepresentation.UNSIGNED);

        float[] imagerPixelSpacing = new float[2];
        imagerPixelSpacing[0] = (float) (1.0 / ii.getPhysicalWidthDpi() * mmPerInch);
        imagerPixelSpacing[1] = (float) (1.0 / ii.getPhysicalHeightDpi() * mmPerInch);
        getDXDetectorModule().setImagerPixelSpacing(imagerPixelSpacing);
        getDXDetectorModule().setPixelSpacing(imagerPixelSpacing);

        fiis.seek(0);

    }

    private void setOrientation(float prim, float sec, ViewCode viewcode) {
        getDXPositioningModule().setPositonerPrimaryAngle(prim);
        getDXPositioningModule().setPositonerSecondaryAngle(sec);
        getDXPositioningModule().setViewCode(viewcode);
    }

    private Code setCode(Code c, String val, String mean, String desig) {
        c.setCodeValue(val);
        c.setCodeMeaning(mean);
        c.setCodingSchemeDesignator(desig);
        return c;

    }

    private String makeInstanceUID() {
        return UIDUtils.createUID();
    }

    public String toString() {
        return dcmobj.toString();
    }

}
