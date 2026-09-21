package de.embl.iclm;

import org.scijava.prefs.DefaultPrefService;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Roi;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.plugin.filter.PlugInFilterRunner;

import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.swing.UIManager;

public class Parameter {
	//	current object instantce
	protected static Parameter instance;
	//	fixed value parameter for all calling object
	protected static final double zSizePerGalvoDU       = 13.25d;
	protected static final String loadSettingMessage    = "          " + "load settings from file";
	protected static final String loadAlignMessage      = "          " + "load alignment matrix from file";
	protected static final String loadRoiMessage        = "          " + "load ROI from file";
	protected static final String[] extensions          = {"tif", "tiff"};
	
	/**
	 * The background every OPM window and dialog is painted with.
	 *
	 * <p>Shared so the toolset reads as one plugin rather than a dozen unrelated commands.
	 * It used to be private here and separately re-declared as {@code panelColor} in each
	 * Swing frame, which meant new dialogs silently came up in the default grey.
	 */
	public static final Color frameColor                = new Color (204, 229, 255);

	/**
	 * The font every OPM dialog is drawn with, taken from the Swing look and feel.
	 *
	 * <p>The Live Deskew setup is a Swing dialog and gets this font from the look and feel.
	 * The batch dialogs are {@link GenericDialog}s of AWT widgets, and an AWT widget with no
	 * font of its own falls back to the toolkit default - Dialog plain 12 - rather than to
	 * anything the desktop configured. On Windows those widgets are native and the two agree
	 * anyway. On Linux they are drawn by Java2D, and Dialog plain 12 beside the desktop font
	 * of a modern Ubuntu is both smaller and a different face; that is the difference this
	 * removes between the Deskew Batch dialog and the Live one built from the same jar.
	 *
	 * <p>It does not remove all of it. Swing draws its text with the desktop's antialiasing
	 * hints and the XAWT widget peers do not, which no font can change - only building the
	 * dialog in Swing would, and the batch dialogs are deliberately {@code GenericDialog}s.
	 *
	 * <p>Scaled by ImageJ's GUI scale (Edit &gt; Options &gt; Appearance), because that is the
	 * knob a user reaches for when a dialog is too small to read, and because
	 * {@link GenericDialog#addMessage(String, Font)} already scales the fonts handed to it.
	 * Leaving the rest of the dialog unscaled is what made the section headings grow while
	 * the fields they head did not.
	 */
	public static Font dialogFont () {
		Font font = null;
		try { font = UIManager.getFont ( "Label.font" ); }
		catch (Exception ignored) { /* no look and feel: fall through to the default below */ }
		if (font == null) font = new Font ( "SansSerif", Font.PLAIN, 12 );
		double scale = Prefs.getGuiScale();
		if (scale != 1.0d && scale > 0d)
			font = font.deriveFont ( (float) ( font.getSize() * scale ) );
		return font;
	}

	/**
	 * The same font in bold, for the section headings both dialogs are divided into.
	 *
	 * <p>Bold is added to whatever weight the look and feel already asked for rather than
	 * replacing it. Metal's {@code Label.font} is itself bold, so under it a heading reads the
	 * same as the rows beneath it - which is what the Live setup has always looked like there,
	 * and following the look and feel is the point.
	 */
	public static Font sectionFont () {
		Font base = dialogFont();
		return base.deriveFont ( base.getStyle() | Font.BOLD );
	}

	/**
	 * Give a {@link GenericDialog} the shared background and the shared font.
	 *
	 * <p>AWT children with no font of their own inherit the dialog's, so one call reaches the
	 * labels, checkboxes, choices, text fields and buttons.
	 *
	 * <p>The font is set twice on purpose. {@link GenericDialog#setFont} multiplies by the GUI
	 * scale, but only until it has been called once, and its constructor has usually called it
	 * already - so whether our font arrives scaled or not depends on state we cannot read.
	 * Setting it, checking what arrived and setting it again removes the guess: the second
	 * call is never scaled, and {@link #dialogFont()} has applied the scale itself.
	 */
	public static void styleDialog (GenericDialog gd) {
		/* Debug.background() is the window theme's colour, normally frameColor; the dialog is
		 * given the colour it should already be wearing rather than being repainted after it
		 * is on screen. */
		gd.setBackground ( Debug.background() );
		Font font = dialogFont();
		gd.setFont ( font );
		Font applied = gd.getFont();
		if (applied == null || applied.getSize() != font.getSize()) gd.setFont ( font );
	}

	/**
	 * Add a section heading to a {@link GenericDialog}, in the face the Live setup uses.
	 *
	 * <p>The font is set on the label afterwards rather than passed to
	 * {@link GenericDialog#addMessage(String, Font)}, which would scale it by the GUI scale a
	 * second time.
	 *
	 * <p>The heading is named {@link SectionFolds#HEADING}: in an {@link OpmDialog} or
	 * {@link OpmDialogPlus} it becomes a fold, and everything added after it, up to the next
	 * heading, folds away under it.
	 */
	public static void addSection (GenericDialog gd, String text) {
		gd.addMessage ( text );
		Component label = gd.getMessage();
		if (label == null) return;
		label.setName ( SectionFolds.HEADING );
		label.setFont ( sectionFont() );
		// remembered so the headings change colour with the rest of the window
		Debug.rememberHeading ( label );
	}

	/**
	 * Apply the shared font to a Swing tree, keeping each component's own bold or italic.
	 *
	 * <p>A Swing container does not pass its font down to children that already have one, and
	 * every component the look and feel builds has one. This is how the Live setup follows the
	 * GUI scale without its section headings losing their bold.
	 */
	public static void applyFont (Container root, Font font) {
		for (Component child : root.getComponents()) {
			Font own = child.getFont();
			child.setFont ( own == null ? font : font.deriveFont ( own.getStyle() ) );
			if (child instanceof Container) applyFont ( (Container) child, font );
		}
	}

	/**
	 * How a rigid transform samples its source, offered wherever one is applied.
	 *
	 * <p>Bilinear blends the neighbouring pixels and places features at their true sub-pixel
	 * position. Nearest neighbour copies one source pixel unchanged: it misplaces a feature by
	 * up to half a pixel, but it invents no intensity value, so photon counts, Poisson noise
	 * and anything quantitative downstream survive the transform untouched.
	 */
	public static final String INTERPOLATION_BILINEAR   = "bilinear";
	public static final String INTERPOLATION_NEAREST    = "nearest neighbour";
	public static final String[] INTERPOLATION_OPTIONS  = {
		INTERPOLATION_BILINEAR, INTERPOLATION_NEAREST
	};

	/**
	 * What a batch or live run writes to disk.
	 *
	 * <p>One dropdown rather than two independent checkboxes, because the two formats are not
	 * two independent switches: OME-Zarr already carries its own projections inside the
	 * dataset, so a Zarr-only run must not also scatter per-projection TIFF sub-folders beside
	 * it. Asking "which format" once makes that decision derivable instead of a rule the user
	 * has to remember.
	 */
	public static final String FORMAT_TIFF              = "save as TIFF";
	public static final String FORMAT_ZARR              = "save as OME-Zarr";
	public static final String FORMAT_BOTH              = "save both";
	public static final String[] OUTPUT_FORMATS         = { FORMAT_TIFF, FORMAT_ZARR, FORMAT_BOTH };

	/** The dropdown entry for a stored flag. */
	public static String interpolationChoice (boolean bilinear) {
		return bilinear ? INTERPOLATION_BILINEAR : INTERPOLATION_NEAREST;
	}

	/** Read a dropdown entry back; anything unrecognised keeps the bilinear default. */
	public static boolean isBilinear (String choice) {
		return !INTERPOLATION_NEAREST.equals(choice);
	}

	/**
	 * How a raw volume's two mirrored camera halves are turned into results.
	 *
	 * <p>Public and static because the live setup is a Swing dialog of its own rather than a
	 * {@code GenericDialog}: it has to build the same dropdown from the same list, and a
	 * second hand-written copy would be a second place for the option names to drift.
	 */
	public static final String[] CHANNEL_OPTIONS        = {"whole image", "fold by midline",
		"align with SIFT", "only left", "only right", "left & right separately"};
	private final String[] channelOptions               = CHANNEL_OPTIONS;
	private final String[] fileExistOptions             = {"skip", "overwrite"};
	private final String[] typeChoices                  = {"translate","scale","rotate","shear_X", "shear_Y", "shear_Z"};
	private final String[] axisChoices                  = {"X", "Y", "Z"};
	private final String[] permuteOptions               = {"->YZX", "->ZYX", "->XZY", "->ZXY", "->YXZ", "->XYZ"};
	private final String[] imageTypes                   = {"auto detection", "OPM raw volume", "deskewed volume"};
	private final String[] psfAvgMethod                 = {"median average", "mean average", "all beads"};
	public static final String[] PSF_CHANNEL_LAYOUTS    = {"single channel image", "mirrored left/right halves"};
	private final String[] deconvMethodChoices          = {"Richardson-Lucy (FFT)", "Richardson-Lucy Total Variation"};
	
	
	//	parameter call object
	public  String obj                                  = "";	
	
	//	parameters for deskew active image
	public ImagePlus impInput                           = null;
	@Persist public double xyPixelSize                  = 116.0d;
	@Persist public double zStepSize                    = 132.5d;
	@Persist public double opmAngle                     = 25.0d;
	public double frameInterval                         = 0.0d;
	@Persist public String deskewmFile                  = loadSettingMessage;
	protected double[][] deskewMatrix                   = Transform.identity();
	@Persist public String channelStr                   = channelOptions[0];
	@Persist public String alignmFile                   = loadAlignMessage;
	protected double[][] alignMatrix                    = null;
	/** All source-to-reference bead transforms; null for no matrix file. */
	protected AlignmentMatrixSet alignmentMatrices      = null;
	protected boolean displayResult                     = true;		// by default display result image(s), and no display for batch, and folder watch
	protected boolean doProjection                      = true;		// whether to make projection images (hidden for user, and based on projection options)
	@Persist public String saveDir                      = "";
	@Persist public boolean saveToSame                  = false;
	@Persist public boolean saveDeskewImage             = true;
	/** Write the six orthogonal projections beside the volume; OME-Zarr always carries them. */
	@Persist public boolean saveProjectionViews         = true;
	/** Save one canonical acquisition-level OME-Zarr dataset in addition to optional TIFFs. */
	@Persist public boolean saveDeskewZarr              = false;
	/** Which of TIFF, OME-Zarr or both a batch/live run writes; see {@link #applyOutputFormat}. */
	/**
	 * Default OME-Zarr, not TIFF.
	 * <p>
	 * It is the format the previews read: a virtual view of a dataset still being written is
	 * only possible over a store whose time points commit one at a time. A TIFF run can
	 * preview projections but not the volume.
	 */
	@Persist public String outputFormat                 = FORMAT_ZARR;
	/** Live writer: sequential _Channel0001..N files required before a time point commits. */
	@Persist public int zarrExpectedAcquisitionChannels = 2;
	/**
	 * Whether the live listener binds its TCP/IP port at all.
	 * <p>
	 * Declared here rather than beside the other live settings so that both halves of the
	 * v2.1.6 audit repair can proceed without editing the same block: the folder-only startup
	 * path that consumes it lives in Live2 and LiveSetupDialog.
	 */
	@Persist public boolean listenTcpIp                 = true;
	@Persist protected boolean saveDeskewMatrix         = false;
	@Persist protected boolean saveAlignMatrix          = false;
	protected String fileNameDeskew                     = "<image name>_deskew.csv";
	protected String fileNameAlign                      = "<image name>_align.csv";
	
	// parameters for deconvolution
	
	public Roi roiInput                                 = null;
	public ImagePlus impPSF                             = null;
    @Persist public String deconvMethod                 = deconvMethodChoices[0];
    @Persist public int numIter                         = 10;
    @Persist public double regFactor                    = 0.0d;
	@Persist public boolean loadFromFile                = false;
	@Persist public String beadsPath                    = "";
	@Persist public String imageType                    = imageTypes[0];
	@Persist public boolean loadFromManager             = false;
	@Persist public int radiusXY                        = 17;
	@Persist public int radiusZ                         = 35;
	@Persist public int beadsCount                      = 100;
	@Persist public boolean addToManager                = false;
	@Persist public String avgMethod                    = psfAvgMethod[1];
	@Persist public String psfChannelLayout             = PSF_CHANNEL_LAYOUTS[0];
	@Persist public boolean psfFlipRight                = true;
	@Persist public double psfShellFraction             = 0.15d;
	@Persist public double psfMinSnr                    = 5.0d;
	@Persist public double psfMinSbr                    = 1.5d;
	@Persist public double psfMaxCenterOffset           = 0.5d;
	@Persist public double psfSaturationLevel           = 0.0d;
	@Persist public boolean psfRejectNeighbors          = true;
	@Persist public String roiPath                      = loadRoiMessage;
	public String zRangeStr                             = "1-end";
	
	
	//	parameters for batch processing
	@Persist public String inputDir                     = "";
	@Persist public String keywords                     = "";
	@Persist public boolean recursive                   = false;
	@Persist protected boolean doDeskew                 = true;
	/**
	 * Collect projections into a displayed/saved time-lapse.
	 * <p>
	 * No longer offered by either deskew dialog. Projections are previewed through the
	 * OME-Zarr viewer now, and a projection time-lapse TIFF is a few Fiji operations away from
	 * the projections that are already written. {@code Generate Projection Image} still offers
	 * it, because building those movies is that command's whole purpose.
	 */
	@Persist protected boolean makeTimeLapse            = false;
	protected boolean displayTimeLapse                  = true;		// by default, always display time-lapse if makeTimeLapse is enabled
	@Persist public boolean saveSeparate                = false;
	@Persist public String fileExistStr                 = fileExistOptions[0];
	@Persist public String logPath                      = "";
	
	//	parameters for folder watch
	@Persist public String watchDir                     = "";
	@Persist public int maxWait                         = 1000;
	@Persist public boolean processOld                  = false;
	@Persist public boolean overwriteExist              = false;
	/** File name fragments a live/batch run must NOT pick up; comma separated, empty accepts all. */
	@Persist public String excludeKeywords              = "";
	/** Watch the folder an announced file path points into, as well as any explicit watch folder. */
	@Persist public boolean watchAnnouncedFolder        = true;
	/** Whether the explicitly configured {@link #watchDir} is watched at all. */
	@Persist public boolean watchExplicitFolder         = false;
	/** Ignore ExperimentalParameters.txt and deskew with the values typed into the dialog. */
	@Persist public boolean manualDeskewParameters      = false;
	/** Mirror the input folder tree under the result folder instead of flattening it. */
	@Persist public boolean reproduceInputTree          = false;
	/** Remembers whether the live setup dialog was last left in advanced mode. */
	@Persist public boolean liveAdvancedMode            = false;
	/** Keep an always-virtual view of the acquisition on screen while it is being written. */
	@Persist public boolean livePreview                 = true;
	/** Preview the projection movie; for a TIFF-only run this is the MIP movie window. */
	@Persist public boolean livePreviewProjection       = true;
	/** Preview the deskewed volume as a virtual 5-D stack; needs OME-Zarr output. */
	@Persist public boolean livePreviewVolume           = true;
	/**
	 * Open previews as virtual stacks rather than materialising them.
	 * <p>
	 * On by default and rarely worth changing. A materialised preview loads every plane before
	 * the window opens and cannot follow new time points, so it is offered for the projection
	 * movie only - a 5-D volume of an acquisition in progress has to be virtual.
	 */
	@Persist public boolean previewVirtual              = true;
	
	// parameters for TCP-IP client
	@Persist public int port                            = 5020;
	
	//	parameters for volume transformation	//TODO: try preview
	protected int nTransform                            = 0;
 	protected List<Boolean> apply		= new ArrayList<Boolean>();
 	protected List<String> type			= new ArrayList<String>();
 	protected List<String> axis			= new ArrayList<String>();
 	protected List<Double> value		= new ArrayList<Double>();
 	protected List<Boolean> display		= new ArrayList<Boolean>();
 	protected String axisPartition		= "";
 	protected String axisCombine		= "";
	
	//	parameters for axis permutation			//TODO: try preview
 	@Persist protected boolean flipX                    = false;
 	@Persist protected boolean flipY                    = false;
 	@Persist protected boolean flipZ                    = false;
 	@Persist protected boolean foldX                    = false;
 	@Persist protected String permuteStr                = permuteOptions[5];
	
	//	parameters for axis projection			//TODO: try preview
 	@Persist public boolean projX                       = false;
 	@Persist public boolean projY                       = false;
 	@Persist public boolean projZ                       = false;
 	@Persist public boolean maxProj                     = false;
 	@Persist public boolean avgProj                     = false;
 	@Persist public boolean minProj                     = false;
 	@Persist public boolean sumProj                     = false;
 	@Persist public boolean medProj                     = false;
 	@Persist public boolean stdProj                     = false;
	protected List<String> projAxes                     = new ArrayList<String>();
	protected List<String> projTypes                    = new ArrayList<String>();
	protected String projAxis                           = null;		// parameter local to Projection class ?
	protected String projType                           = null;		// parameter local to Projection class ?

	
	//	parameters for debugging
	@Persist protected boolean doInverse                = false;	// whether to perform inverse transform
	@Persist protected boolean doVirtual                = false;	// imglib2 transform result as Virtual stack
	@Persist public boolean tryGPU                      = true;		// do processing on GPU
	@Persist protected boolean stepTransform            = false;	// do transformation step by step: shear, scale, (translate), rotate, (translate)
	@Persist protected boolean autoPartition            = true;		// whether to automatically partition data
	@Persist protected int numPartition                 = 8;		// in case of manual setup, the number of data partitions
	
	

	
	
	
	/**		Marks a field that survives between Fiji sessions
	 * <br>	Load and store used to be two hand-written lists of the same sixty-odd keys, which
	 * <br>	could disagree, and adding one setting meant editing both. Both are now driven from
	 * <br>	the annotated fields themselves, so a new persisted setting is one annotation.
	 * <p>	The preference key stays "OPM-&lt;obj&gt;-&lt;field name&gt;", exactly what the two
	 * <br>	lists spelled out, so settings saved by earlier versions are still read.
	 * <p>	Supported field types: double, int, boolean and String.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	@interface Persist {}

	/**			Preference key for one persisted field of this parameter set
	 *
	 * @param field	: a field annotated with @Persist
	 * <p>
	 * @return		: the SciJava preference key, scoped to this dialog's obj name
	 */
	private String prefKey ( Field field ) {
		return "OPM-" + obj + "-" + field.getName();
	}

	/**			Every field of this class that is marked for persistence
	 * <p>
	 * @return	: the annotated fields, in declaration order
	 */
	private static List<Field> persistedFields () {
		List<Field> fields = new ArrayList<Field>();
		for ( Field field : Parameter.class.getDeclaredFields() ) {
			if ( field.isAnnotationPresent( Persist.class ) ) {
				field.setAccessible ( true );	// the persisted set spans public and protected
				fields.add ( field );
			}
		}
		return fields;
	}

	/** 		generic constructor for Parameter class
	 * <br>		Restores every persisted setting for this dialog, falling back to the field's
	 * <br>		own default whenever no preference was stored yet.
	 *
	 * @param obj	: name of the operation these parameters belong to: image, batch, watcher...
	 */
	Parameter(String obj) {
		instance = this;
		this.obj = obj;
		loadParam();
	}

	/** Neither loads preferences nor claims the static; see {@link #scratch}. */
	private Parameter() {
		this.obj = "scratch";
	}

	/**			A settings object that belongs to nobody
	 * <p>		{@link Partition#processMap} reads exactly five fields - the deskew matrix, the
	 * 			projection type, the permute string and the three flip flags - so a Parameter
	 * 			handed to it is an argument carrier, not a user's settings. The processing
	 * 			classes used to reach for {@link #getInstance} instead, which meant a hyperstack
	 * 			operation <em>wrote</em> its own arguments into whichever dialog happened to be
	 * 			constructed last: a projection running inside a live acquisition could overwrite
	 * 			the Batch dialog's projection type, and two of those fields are persisted, so the
	 * 			transient value could even reach the user's saved preferences.
	 * <p>		This constructor deliberately skips both {@code loadParam()} and the assignment
	 * 			to {@code instance}: a scratch object must be cheap and must not become what the
	 * 			next {@code getInstance()} hands back.
	 *
	 * @return					: a fresh Parameter owned only by its caller
	 */
	static Parameter scratch () {
		return new Parameter();
	}

	/**		Restore every @Persist field from the SciJava preference store
	 * <br>	A field whose stored value cannot be read keeps its declared default rather than
	 * <br>	aborting the whole dialog, so one bad preference cannot stop the plugin opening.
	 */
	public void loadParam () {
		DefaultPrefService prefs = new DefaultPrefService();
		for ( Field field : persistedFields() ) {
			String key = prefKey ( field );
			try {
				Class<?> valueType = field.getType();
				if ( double.class.equals(valueType) )
					field.setDouble ( this, prefs.getDouble ( Double.class, key, field.getDouble(this) ) );
				else if ( int.class.equals(valueType) )
					field.setInt ( this, prefs.getInt ( Integer.class, key, field.getInt(this) ) );
				else if ( boolean.class.equals(valueType) )
					field.setBoolean ( this, prefs.getBoolean ( Boolean.class, key, field.getBoolean(this) ) );
				else if ( String.class.equals(valueType) )
					field.set ( this, prefs.get ( String.class, key, (String) field.get(this) ) );
				else
					System.out.println(" parameter " + field.getName() + " has no persistence for type " + valueType);
			} catch ( Exception e ) {
				System.out.println(" could not restore parameter " + key + " : " + e);
			}
		}
	}

	/**		Write every @Persist field to the SciJava preference store
	 */
	public void storeParam () {
		DefaultPrefService prefs = new DefaultPrefService();
		for ( Field field : persistedFields() ) {
			String key = prefKey ( field );
			try {
				Class<?> valueType = field.getType();
				if ( double.class.equals(valueType) )			prefs.put ( Double.class, key, field.getDouble(this) );
				else if ( int.class.equals(valueType) )			prefs.put ( Integer.class, key, field.getInt(this) );
				else if ( boolean.class.equals(valueType) )		prefs.put ( Boolean.class, key, field.getBoolean(this) );
				else if ( String.class.equals(valueType) )		prefs.put ( String.class, key, (String) field.get(this) );
			} catch ( Exception e ) {
				System.out.println(" could not store parameter " + key + " : " + e);
			}
		}
	}
	
	
	/**			return current Parameter object
	 * <p>		create new empty Parameter object if null exist
	 * <br>		LEGACY. The constructor assigns instance, so this hands back the settings of whichever
	 * <br>		dialog was opened last. The processing classes use it to reach the projection
	 * <br>		type and the partition settings without threading a Parameter through every call.
	 * <p>
	 * @return	: the current parameter set; never null
	 */
	public static Parameter getInstance () {
		if (null == instance)
			return new Parameter(""); // TODO check if this is correct?
		else
			return (Parameter) instance;
	}
	
	
	/**				Create non-modal parameter dialog for Deskew + obj + command
	 * <br>			Non-modal so the user can pick a different image, draw an ROI, or scroll
	 * <br>			through the stack while the dialog is open and the preview follows.
	 *
	 * @param command	: menu command name, used as the dialog title
	 * @param pfr		: filter runner the preview checkbox needs
	 * <p>
	 * @return			: the dialog, not yet shown
	 */
	public GenericDialog deskew_image ( String command, PlugInFilterRunner pfr ) {
		// create non-modal parameter dialog with preview functionality
		GenericDialog gd = new OpmDialog(command);
		styleDialog( gd );
		int length_string_field = 35;
		int left_inset_checkbox = 119;
		int top_inset_section = 20;
		
        gd.addImageChoice("select active image", this.impInput.getTitle());
        
        gd.setInsets(top_inset_section, 0, 5);
        gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
        gd.addSlider("Z step size (nm)", 0, 530, zStepSize, 0.1);
        gd.addSlider("OPM angle (°)", -90, 90, opmAngle, 0.1);
        if (null == deskewmFile || deskewmFile.isEmpty()) deskewmFile = loadSettingMessage;
        gd.addFileField("", deskewmFile, length_string_field);
        
        gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("inverse transform", doInverse);
		
		gd.setInsets(top_inset_section, 0, 10);
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.setInsets(0, 0, 0);
		if (null == alignmFile || alignmFile.isEmpty()) alignmFile = loadAlignMessage;
		gd.addFileField("align matrix", alignmFile, length_string_field);
		
		gd.setInsets(top_inset_section, 15, 0);
		gd.addMessage("show projection image(s):");
		String[] label_axis = {"along_X      ", "along_Y      ", "along_Z      "};
		boolean[] state_axis = {projX, projY, projZ};
		gd.setInsets(0, left_inset_checkbox, 5);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);
		
		String[] label_type = {"maximum", "mean"};//, "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj};//, minProj, sumProj, medProj, stdProj};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		
		gd.setInsets(top_inset_section, left_inset_checkbox, 10);
		gd.addButton("save deskew setting", new ActionListener() { 
			public void actionPerformed(ActionEvent e) { deskew_saveSetting (); }
		});
		
		gd.setInsets(top_inset_section, left_inset_checkbox, 0);
        gd.addPreviewCheckbox( pfr );
        
		gd.addHelp(Help.deskew);

        return gd;
	}
	/**			Ask where to save the deskew and alignment matrices, and remember the answer
	 * <br>		Offered as a second dialog from the deskew dialog's own button, so the file names
	 * <br>		and the folder are only asked for when the user actually wants them written.
	 */
	public void deskew_saveSetting () {
		GenericDialog gd = new OpmDialogPlus("Save Deskew Setting");
		styleDialog( gd );
		int length_string_field = 35;
		int left_inset_checkbox = 87;
		
		gd.addDirectoryField("save to folder", saveDir, 30+length_string_field);
		gd.setInsets(10, left_inset_checkbox, 5);
		gd.addCheckbox("save deskew matrix (affine 3D)", saveDeskewMatrix);
		gd.addToSameRow();
		gd.addStringField("", fileNameDeskew, length_string_field);
		gd.setInsets(5, left_inset_checkbox, 10);
		gd.addCheckbox("save alignment matrix (rigid 2D)", saveAlignMatrix);
		gd.addToSameRow();
		gd.addStringField("", fileNameAlign, length_string_field);
        gd.showDialog();
        if (gd.wasCanceled()) return;
        saveDir = gd.getNextString();
        saveDeskewMatrix = gd.getNextBoolean();
        fileNameDeskew = gd.getNextString();
        saveAlignMatrix = gd.getNextBoolean();
        fileNameAlign = gd.getNextString();
        // parse save path
        File saveFolder = new File(saveDir);
        if ( !saveFolder.exists() ) return;
        if ( !fileNameDeskew.endsWith(".csv") ) fileNameDeskew += ".csv";
        if ( !fileNameAlign.endsWith(".csv") ) fileNameAlign += ".csv";
        storeParam (); 	// this here only saves saveDir
	}
	
	
	/**				Create parameter dialog for Batch Processing command
	 * <p>
	 * @return	: false if the user cancelled
	 */
	/**				Create the parameter dialog for Deskew Batch Processing
	 * <p>			Laid out in the same sections, and with the same wording, as the Live Deskew
	 * 				setup. The two commands do the same job - one over a folder, one over an
	 * 				acquisition as it arrives - and a setting that means the same thing in both
	 * 				should read the same in both.
	 * <p>			Fields that another tick makes meaningless are greyed out rather than
	 * 				silently ignored: the result folder while results go beside the data, the
	 * 				manual geometry while it is read from a parameter file, the TIFF layout
	 * 				options while the format is OME-Zarr.
	 * <p>			The controls that drive the greying are captured as they are added rather
	 * 				than looked up by index afterwards. An index into getCheckboxes() is only
	 * 				correct until someone inserts a row above it, and is wrong silently.
	 * <p>
	 * @return	: false if the user cancelled
	 */
	public boolean deskew_batch () {
		final ChannelOperationSettings channels = new ChannelOperationSettings();
		channels.load();

		final NonBlockingGenericDialog gd = new OpmDialog("Batch Processing - Deskew");
		styleDialog( gd );
		final int length_string_field = 55;
		final int left_inset_checkbox = 95;
		final int top_inset_section = 20;

		gd.setInsets(0, 15, 5);
		addSection(gd, "Input setup:");
		gd.addDirectoryField("input folder", inputDir, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("recursively check sub-folders", recursive);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addMessage("file name include (separate multiple by \",\")");
		gd.addStringField("", keywords, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addMessage("file name exclude");
		gd.addStringField("", excludeKeywords, length_string_field);

		gd.setInsets(top_inset_section, 15, 5);
		addSection(gd, "Deskew parameters:");
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("overwrite with manual input", manualDeskewParameters);
		final Checkbox chkManual = lastCheckbox(gd);
		gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
		final TextField xyField = lastStringOrNumber(gd.getNumericFields());
		gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
		final TextField zField = lastStringOrNumber(gd.getNumericFields());
		gd.addNumericField("OPM angle", opmAngle, 1, 5, "degree");
		final TextField angleField = lastStringOrNumber(gd.getNumericFields());
		if (null == deskewmFile || deskewmFile.isEmpty()) deskewmFile = loadSettingMessage;
		gd.addFileField("", deskewmFile, length_string_field);
		final TextField parameterFileField = lastStringOrNumber(gd.getStringFields());

		gd.setInsets(top_inset_section, 15, 5);
		addSection(gd, "Channels:");
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addChoice("interpolation", INTERPOLATION_OPTIONS,
				interpolationChoice(channels.interpolate));
		gd.addFileField("align matrix", alignmFile, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		// two slots to start with; one [-] [+] below the list lengthens and shortens it
		channels.addToDialog(gd, left_inset_checkbox);

		gd.setInsets(top_inset_section, 15, 5);
		addSection(gd, "Projection:");
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 3,
				new String[] { "along X", "along Y", "along Z" },
				new boolean[] { projX, projY, projZ });
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2,
				new String[] { "maximum", "mean" },
				new boolean[] { maxProj, avgProj });

		gd.setInsets(top_inset_section, 15, 5);
		addSection(gd, "Output setup:");
		gd.addDirectoryField("save to", saveDir, length_string_field);
		final TextField saveDirField = lastStringOrNumber(gd.getStringFields());
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save result to the same (data) folder", saveToSame);
		final Checkbox chkToSame = lastCheckbox(gd);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("reproduce input folder structure", reproduceInputTree);
		final Checkbox chkReproduce = lastCheckbox(gd);
		gd.addChoice("format", OUTPUT_FORMATS,
				isOutputFormat(outputFormat) ? outputFormat : FORMAT_ZARR);
		final Choice formatChoice = (Choice) gd.getChoices().lastElement();
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save deskew volume", saveDeskewImage);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save projection views", saveProjectionViews);
		final Checkbox chkSaveProjections = lastCheckbox(gd);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("separate results to sub-folders", saveSeparate);
		final Checkbox chkSeparate = lastCheckbox(gd);
		gd.addChoice("if result exists", fileExistOptions, fileExistStr);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("show combined movie with batch processing progress",
				livePreviewProjection || livePreviewVolume);
		final Checkbox chkShowMovie = lastCheckbox(gd);
		/* Both previews are always virtual, so it is stated rather than asked. A preview that
		 * follows a run in progress has to read planes as it needs them; a materialised one
		 * loads everything up front and then stops following - for the projection movie as
		 * much as for the volume. Materialising is one click away in the viewer it opens. */
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("projection view (virtual)", livePreviewProjection);
		final Checkbox chkPreviewProjection = lastCheckbox(gd);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("deskewed volume (virtual)", livePreviewVolume);
		final Checkbox chkPreviewVolume = lastCheckbox(gd);

		final Runnable refresh = new Runnable() {
			@Override
			public void run () {
				boolean manual = chkManual.getState();
				enable(xyField, manual);
				enable(zField, manual);
				enable(angleField, manual);
				enable(parameterFileField, !manual);

				enable(saveDirField, !chkToSame.getState());
				enable(chkReproduce, !chkToSame.getState());

				String format = formatChoice.getSelectedItem();
				boolean writesTiff = !FORMAT_ZARR.equals(format);
				boolean writesZarr = !FORMAT_TIFF.equals(format);
				// an OME-Zarr dataset carries its six projections and its own layout
				enable(chkSaveProjections, writesTiff);
				enable(chkSeparate, writesTiff);

				boolean movie = chkShowMovie.getState();
				enable(chkPreviewProjection, movie);
				/* Offered for either format now. A TIFF-only run is previewed from its result
				 * folders by the OPM Data Viewer, which reads one plane at a time and takes on
				 * new time points as the files appear. */
				enable(chkPreviewVolume, movie);
			}
		};
		gd.addDialogListener(new DialogListener() {
			@Override
			public boolean dialogItemChanged (GenericDialog dialog, AWTEvent event) {
				refresh.run();
				return true;
			}
		});
		refresh.run();

		gd.addHelp(Help.batch);
		gd.showDialog();
		if (gd.wasCanceled()) return false;

		// read back in exactly the order the controls were added
		inputDir =              gd.getNextString();
		recursive =             gd.getNextBoolean();
		keywords =              gd.getNextString();
		excludeKeywords =       gd.getNextString();
		manualDeskewParameters = gd.getNextBoolean();
		xyPixelSize =           gd.getNextNumber();
		zStepSize =             gd.getNextNumber();
		opmAngle =              gd.getNextNumber();
		deskewmFile =           gd.getNextString();
		channelStr =            gd.getNextChoice();
		channels.interpolate =  isBilinear(gd.getNextChoice());
		alignmFile =            gd.getNextString();
		channels.readFrom(gd);
		projX =                 gd.getNextBoolean();
		projY =                 gd.getNextBoolean();
		projZ =                 gd.getNextBoolean();
		maxProj =               gd.getNextBoolean();
		avgProj =               gd.getNextBoolean();
		saveDir =               gd.getNextString();
		saveToSame =            gd.getNextBoolean();
		reproduceInputTree =    gd.getNextBoolean();
		outputFormat =          gd.getNextChoice();
		saveDeskewImage =       gd.getNextBoolean();
		saveProjectionViews =   gd.getNextBoolean();
		saveSeparate =          gd.getNextBoolean();
		fileExistStr =          gd.getNextChoice();
		boolean showMovie =     gd.getNextBoolean();
		livePreviewProjection = gd.getNextBoolean();
		livePreviewVolume =     gd.getNextBoolean();
		previewVirtual = true;	// no longer asked: see the preview rows above

		/* The first tick is a master over the two that follow, not a third state: unticking
		 * it means "no preview", which neither view on its own says. */
		if (!showMovie) { livePreviewProjection = false; livePreviewVolume = false; }
		livePreview = livePreviewProjection || livePreviewVolume;

		/* Not offered here any more: projections are previewed through the OME-Zarr viewer,
		 * and the ones written to disk are a few Fiji operations away from a time-lapse. */
		makeTimeLapse = false;

		if (saveToSame) { saveDir = ""; recursive = false; }
		channels.store();
		storeParam ();
		return true;
	}

	/** The control just added, so the greying rules hold a reference instead of an index. */
	static Checkbox lastCheckbox (GenericDialog gd) {
		return (Checkbox) gd.getCheckboxes().lastElement();
	}

	static TextField lastStringOrNumber (java.util.Vector<?> fields) {
		return (TextField) fields.lastElement();
	}

	static void enable (Component component, boolean on) {
		if (component != null) component.setEnabled(on);
	}


	/**				Create parameter dialog for generate PSF from Beads image(s)
	 * <p>
	 * @return	: false if the user cancelled
	 */
	public boolean deconv_psf () {
		// create parameter dialog
		NonBlockingGenericDialog gd = new OpmDialog("Generate experimental PSF from Beads Stack");
		styleDialog( gd );
		
		gd.setInsets(0, 55, 5);
		addSection( gd, "Beads image setup:" );
		impInput = WindowManager.getCurrentImage();
		if ( null != impInput ) {
			gd.setInsets(0, 91, 0);
			gd.addMessage("select active image");
			gd.addImageChoice("", impInput.getTitle());
			gd.setInsets(0, 93, 0);
			gd.addCheckbox("or load from file", loadFromFile);
		}
		gd.addDirectoryField("folder path", beadsPath, 20);
		gd.setInsets(0, 93, 5);
		gd.addCheckbox("including subfolder(s)", recursive);
		gd.addChoice("image type", imageTypes, imageType);
		gd.addChoice("channel layout", PSF_CHANNEL_LAYOUTS, psfChannelLayout);
		gd.addCheckbox("flip the mirrored right half", psfFlipRight);
		gd.setInsets(5, 90, 10);
		gd.addButton("prepare beads image", new ActionListener() { 
			public void actionPerformed(ActionEvent e) { deconv_prepareBeadsImage (Parameter.this, gd); }
		});
		if ( null != impInput ) {
			gd.setInsets(15, 40, 5);
			gd.addCheckbox("Load beads center position from ROI Manager", loadFromManager);
		}
		
		gd.setInsets(25, 55, 5);
		addSection( gd, "PSF image setup:" );
		gd.addNumericField("XY radius", radiusXY, 0, 5, "pixel");
		gd.addNumericField("Z radius", radiusZ, 0, 5, "pixel");
		gd.addNumericField("~ beads count", beadsCount, 0, 5, "");
		if ( null != impInput ) {
			gd.setInsets(0, 93, 0);
			gd.addCheckbox("point to ROI Manager", addToManager);
		}
		gd.setInsets(0, 0, 25);
		gd.addChoice("result as", psfAvgMethod, avgMethod);
		gd.setInsets(0, 55, 5);
		addSection( gd, "Bead quality control:" );
		gd.addNumericField("background shell fraction", psfShellFraction, 2);
		gd.addNumericField("minimum peak SNR", psfMinSnr, 2);
		gd.addNumericField("minimum peak/background ratio", psfMinSbr, 2);
		gd.addNumericField("maximum normalized center offset", psfMaxCenterOffset, 2);
		gd.addNumericField("saturation level (0 = native maximum)", psfSaturationLevel, 1);
		gd.addCheckbox("reject candidates with a nearby bead", psfRejectNeighbors);
		gd.addHelp(Help.PSF);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        
        if ( null != impInput ) {
        	impInput = 		gd.getNextImage();
        	loadFromFile =	gd.getNextBoolean();
        }
        beadsPath = 	gd.getNextString();
        recursive =		gd.getNextBoolean();
        imageType = 	gd.getNextChoice();
		psfChannelLayout = gd.getNextChoice();
		psfFlipRight = gd.getNextBoolean();
        if ( null != impInput ) {
        	loadFromManager = gd.getNextBoolean();
		}
        radiusXY = 		(int) gd.getNextNumber();
        radiusZ = 		(int) gd.getNextNumber();
        beadsCount =	(int) gd.getNextNumber();
        if ( null != impInput ) {
        	addToManager =	gd.getNextBoolean();
        }
        avgMethod = 	gd.getNextChoice();
		psfShellFraction = Math.max(0.01d, Math.min(0.49d, gd.getNextNumber()));
		psfMinSnr = Math.max(0.0d, gd.getNextNumber());
		psfMinSbr = Math.max(0.0d, gd.getNextNumber());
		psfMaxCenterOffset = Math.max(0.0d, gd.getNextNumber());
		psfSaturationLevel = Math.max(0.0d, gd.getNextNumber());
		psfRejectNeighbors = gd.getNextBoolean();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	public void deconv_prepareBeadsImage ( Parameter parameter, GenericDialog dialog ) {
		this.obj = parameter.obj;
		GenericDialogPlus gd = new OpmDialogPlus("Beads Image Preparation");
		styleDialog( gd );

		gd.setInsets(0, 55, 5);
		addSection( gd, "deskew parameters:" );
		
		gd.setInsets(5, 35, 0);
		gd.addNumericField("XY pixel size", xyPixelSize, 1, 5, "nm");
		gd.setInsets(5, 35, 0);
		gd.addNumericField("Z step size", zStepSize, 1, 5, "nm");
		gd.setInsets(5, 35, 0);
		gd.addNumericField("OPM angle", opmAngle, 1, 3, "°");
		
		gd.setInsets(25, 55, 5);
		addSection( gd, "ROI options:" );
		gd.setInsets(0, 80, 0);
		gd.addMessage("ROI from input image if exist");
			roiPath = loadRoiMessage;
		gd.addFileField("or", roiPath, 20);
		gd.addStringField("Z range", zRangeStr, 5);
		
		gd.addHelp(Help.PSF_prepare);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		
		xyPixelSize =	gd.getNextNumber();
        zStepSize = 	gd.getNextNumber();
        opmAngle = 		gd.getNextNumber();
        roiPath =		gd.getNextString();
        zRangeStr =		gd.getNextString();
        
        storeParam ();
	}
	
	
	/**				Create parameter dialog for Richardson-Lucy deconvolution
	 * <p>
	 * @return	: false if the user cancelled
	 */
	public boolean deconv_rlfft () {
		// create parameter dialog
		String[] titles = WindowManager.getImageTitles();
		if (null == titles || titles.length <= 1) {
			IJ.error("Need both the input image and PSF image open in Fiji to perform deconvolution.");
			return false;
		}
		String imp_title = WindowManager.getCurrentImage().getTitle();
		String PSF_title = "";
		for (int i=0; i<titles.length; i++) {
			if (titles[i].toLowerCase().contains("psf")) {
				PSF_title = titles[i];
				break;
			}
		}
		NonBlockingGenericDialog gd = new OpmDialog("Deconvolution of OPM data");
		styleDialog( gd );
		gd.addImageChoice("input", imp_title);
		gd.addImageChoice("PSF", PSF_title);
		gd.addChoice("method", deconvMethodChoices, deconvMethod);
		gd.addNumericField("number of iterations", numIter);
		gd.addSlider("regularization factor", 0.00, 5e-3, regFactor, 1e-4);
		gd.addHelp(Help.deconv);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        impInput = 			gd.getNextImage();
        impPSF = 			gd.getNextImage();
        deconvMethod = 		gd.getNextChoice();
        numIter = 	  (int) gd.getNextNumber();
        regFactor =			gd.getNextNumber();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	/**				Create parameter dialog for Folder Watcher watcher setup command
	 * <p>
	 * @return	: false if the user cancelled
	 */
	public boolean watcher_setupWatch () {
		// create parameter dialog
		GenericDialogPlus gd = new OpmDialogPlus("OPM Folder Watcher");
		styleDialog( gd );
		int length_string_field = 35;
		int left_inset_checkbox = 248;
		
		gd.addDirectoryField("watch folder...", watchDir, length_string_field);
		gd.addStringField("file name contains(separate mulitple by \",\")", keywords, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 5);
		gd.addCheckbox("also process existing files in folder", processOld);
		gd.setInsets(0, left_inset_checkbox, 5);
		gd.addCheckbox("overwrite exist results", overwriteExist);
		gd.addNumericField("max file writing delay", maxWait, 0, 5, "millisecond");
		gd.addHelp(Help.watch_folder);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        watchDir = 			gd.getNextString();
        keywords = 			gd.getNextString();
        processOld = 		gd.getNextBoolean();    
        overwriteExist = 	gd.getNextBoolean();
        maxWait =     (int) gd.getNextNumber();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	/**				Create parameter dialog for Folder Watcher processing setup command
	 * <p>
	 * @return	: false if the user cancelled
	 */
	public boolean watcher_setupProcessing () {
		// create parameter dialog
		GenericDialogPlus gd = new OpmDialogPlus("OPM Processing Setup");
		styleDialog( gd );
		int length_string_field = 35;
		int left_inset_checkbox = 95;
		int top_inset_section = 20;
		
		
		
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", alignmFile, length_string_field);
		
		gd.setInsets(top_inset_section, 0, 0);
		gd.addMessage("\tcreate projection image(s):");
		String[] label_axis = {"along_X", "along_Y", "along_Z"};
		boolean[] state_axis = {projX, projY, projZ};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
		String[] label_type = {"maximum", "mean"};//, "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj};//, minProj, sumProj, medProj, stdProj};				
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("combine as time lapse", makeTimeLapse);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addDirectoryField("save to...", saveDir, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save result to the same (data) folder", saveToSame);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save deskew image", saveDeskewImage);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("separate results to sub-folders", saveSeparate);
		gd.addHelp(Help.watch_process);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        
        channelStr =	 	gd.getNextChoice();
        alignmFile =		gd.getNextString();
        
        projX = 		gd.getNextBoolean();
        projY = 		gd.getNextBoolean();
        projZ = 		gd.getNextBoolean();
        maxProj = 		gd.getNextBoolean();
        avgProj = 		gd.getNextBoolean();
        makeTimeLapse = gd.getNextBoolean();
        saveDir = 		gd.getNextString();
        saveToSame =	gd.getNextBoolean();
        saveDeskewImage=gd.getNextBoolean();
        saveSeparate = 	gd.getNextBoolean();
        
        // store parameter values
        if (saveToSame)	saveDir = "";
        storeParam ();
		return true;
	}
	
	
	public boolean tcpip () {
		// create parameter dialog
		GenericDialogPlus gd = new OpmDialogPlus("OPM TCP-IP Listener");
		styleDialog( gd );
		int length_string_field = 35;
		int left_inset_checkbox = 95;
		int top_inset_section = 20;
		
		
		gd.addChoice("channel option", channelOptions, channelStr);
		gd.addFileField("align matrix", "", length_string_field);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addMessage("\tcreate projection image(s):");
		String[] label_axis = {"along_X", "along_Y", "along_Z"};
		boolean[] state_axis = {true, true, true};
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
		String[] label_type = {"maximum", "mean"};
		boolean[] state_type = {true, avgProj};				
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckboxGroup(1, 2, label_type, state_type);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("combine as time lapse", true);
		
		gd.setInsets(top_inset_section, 0, 5);
		gd.addDirectoryField("save to...", "", length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save result to the same (data) folder", true);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("save deskew image", true);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox("separate results to sub-folders", true);
		
		gd.addHelp(Help.tcpip);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        
        channelStr =	gd.getNextChoice();
        alignmFile =	gd.getNextString();
        
        projX = 		gd.getNextBoolean();
        projY = 		gd.getNextBoolean();
        projZ = 		gd.getNextBoolean();
        maxProj = 		gd.getNextBoolean();
        avgProj = 		gd.getNextBoolean();
        minProj = 		false;
        sumProj = 		false;
        medProj = 		false;
        stdProj = 		false;
        
        makeTimeLapse = gd.getNextBoolean();
        saveDir = 		gd.getNextString();
        saveToSame =	gd.getNextBoolean();
        saveDeskewImage=gd.getNextBoolean();
        saveSeparate = 	gd.getNextBoolean();
        
        // store parameter values
        if (saveToSame)	saveDir = "";
        File saveFolder = new File(saveDir);
		if ( saveDir.equals("") || null == saveFolder ) saveToSame = true;
    	
    	
		parseDeskewParameterLive();
    	parseProjectionParameter();
    	parseAlignParameter();

		return true;
	}


			
	/**			Create parameter dialog for Axis Projection command
	 * <p>
	 * @return	: false if the user cancelled
	 */
	public boolean axis_projection () {
		// create parameter dialog
		NonBlockingGenericDialog gd = new OpmDialog("Create Projection Image");
		styleDialog( gd );
		gd.addImageChoice("select active image", this.impInput.getTitle());
		String[] label_axis = {"along_X", "along_Y", "along_Z"};
		boolean[] state_axis = {projX, projY, projZ};				
		gd.addCheckboxGroup(1, 3, label_axis, state_axis);		
		String[] label_type = {"maximum", "mean", "minimum", "sum", "median", "standard deviation"};
		boolean[] state_type = {maxProj, avgProj, minProj, sumProj, medProj, stdProj};				
		gd.addCheckboxGroup(2, 3, label_type, state_type);
		gd.addCheckbox("try GPU processing", tryGPU);
		gd.addHelp(Help.projection);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        impInput = 	gd.getNextImage();
        projX = 	gd.getNextBoolean();
        projY = 	gd.getNextBoolean();
        projZ = 	gd.getNextBoolean();
        maxProj = 	gd.getNextBoolean();
        avgProj = 	gd.getNextBoolean();
        minProj = 	gd.getNextBoolean();
        sumProj = 	gd.getNextBoolean();
        medProj = 	gd.getNextBoolean();
        stdProj = 	gd.getNextBoolean();
        tryGPU = 	gd.getNextBoolean();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	/**			Create parameter dialog for Axis Permutation command
	 * <p>
	 * @return	: false if the user cancelled
	 */
	public boolean axis_permutation () {
		// create parameter dialog
		NonBlockingGenericDialog gd = new OpmDialog("Permutate Stack Axis");
		styleDialog( gd );
		gd.addImageChoice("select active image", this.impInput.getTitle());
		String[] label_flip = {"flip X", "flip Y", "flip Z", "fold X"};
		boolean[] state_flip = {flipX, flipY, flipZ, foldX};				
		gd.addCheckboxGroup(1, 4, label_flip, state_flip);
		gd.addChoice("Permutation:  XYZ", permuteOptions, permuteStr);
		gd.addCheckbox("try GPU processing", tryGPU);
		gd.addHelp(Help.permutation);
		gd.showDialog();
        if (gd.wasCanceled()) return false;
        impInput = 		gd.getNextImage();
        flipX = 		gd.getNextBoolean();
        flipY = 		gd.getNextBoolean();
        flipZ = 		gd.getNextBoolean();
        foldX = 		gd.getNextBoolean();
        permuteStr = gd.getNextChoice();
        tryGPU = 		gd.getNextBoolean();
        // store parameter values
        storeParam ();
		return true;
	}
	
	
	
	/**			Create parameter dialog for SIFT Alignment command
	 *
	 * @param siftparam	: SIFT parameters the dialog reads and writes
	 * @param pfr		: filter runner the preview checkbox needs
	 * <p>
	 * @return			: the dialog, not yet shown
	 */
	public GenericDialog sift_alignment ( SIFT.Param siftparam, PlugInFilterRunner pfr ) {
		// create non-modal parameter dialog with preview functionality
		GenericDialog gd = new OpmDialog("Align Channel with SIFT");
		styleDialog( gd );
		int length_string_field = 35;
		int left_inset_checkbox = 140;
		int top_inset_section = 20;
		 
		
		addSection( gd, "Select Active Image:" );
		ImagePlus impInput = siftparam.parameter.impInput;
        gd.addImageChoice("", impInput.getTitle());
        gd.addSlider("slice", 1, impInput.getImageStackSize(), impInput.getSlice(), 1);

		gd.setInsets(top_inset_section, 15, 5);
		addSection( gd, "Scale Invariant Interest Point Detector:" );
		gd.addNumericField( "initial_gaussian_blur:", siftparam.sift.initialSigma, 2, 6, "px" );
		gd.addNumericField( "steps_per_scale_octave:", siftparam.sift.steps, 0 );
		gd.addNumericField( "min_image_size:", siftparam.sift.minOctaveSize, 0, 6, "px" );
		gd.addNumericField( "max_image_size:", siftparam.sift.maxOctaveSize, 0, 6, "px" );
		
		gd.addMessage( "Feature Descriptor:" );
		gd.addNumericField( "descriptor size:", siftparam.sift.fdSize, 0 );
		gd.addNumericField( "orientation bins:", siftparam.sift.fdBins, 0 );
		gd.addNumericField( "closest ratio:", siftparam.rod, 2 );
		
		
		gd.setInsets(top_inset_section, 15, 5);
		addSection( gd, "Geometric Consensus Filter:" );
		gd.addNumericField( "max_alignment_error:", siftparam.maxEpsilon, 2, 6, "px" );
		gd.addNumericField( "min_inlier_ratio:", siftparam.minInlierRatio, 2 );
		gd.addNumericField( "min_inlier_number:", siftparam.minInlierNum, 0 );
		
		gd.setInsets(top_inset_section, 15, 5);
		addSection( gd, "show SIFT points on image:" );
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addCheckbox( "", siftparam.show_sift_points );
		
		
		gd.setInsets(top_inset_section, 15, 5);
		addSection( gd, "Alignment of Channel Image:" );
		gd.setInsets(0, left_inset_checkbox, -5);
		gd.addCheckbox("load alignment matrix from file", siftparam.load_alignMatrix);
		gd.addFileField("align matrix", loadAlignMessage, length_string_field);
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addButton("save alignment matrix", new ActionListener() { 
			public void actionPerformed(ActionEvent e) { SIFT.save_align_matrix (siftparam); }
		});
		gd.setInsets(0, left_inset_checkbox, 0);
		gd.addChoice( "interpolation", INTERPOLATION_OPTIONS,
				interpolationChoice( siftparam.interpolate ) );
		

		
		gd.setInsets(top_inset_section*2, left_inset_checkbox, 0);
        gd.addPreviewCheckbox( pfr );
        
		gd.addHelp(Help.sift);
		
		Vector<Component> choices = gd.getChoices(); 				// 0 : image choice
		Vector<Component> number_fields = gd.getNumericFields();	// 0 - 10
		Vector<Component> check_boxes = gd.getCheckboxes();			// 0 - 3
		Vector<Component> text_fields = gd.getStringFields();		// 0 : matrix file field
		for (int i=0; i<choices.size(); i++) {
			((Component) choices.get(i)).setName( "choice_" + i );
		}
		for (int i=0; i<number_fields.size(); i++) {
			((Component) number_fields.get(i)).setName( "number_field_" + i );
		}
		
		for (int i=0; i<check_boxes.size(); i++) {
			((Component) check_boxes.get(i)).setName( "check_box_" + i );
		}
		
		for (int i=0; i<text_fields.size(); i++) {
			((Component) text_fields.get(i)).setName( "text_field_" + i );
		}
		
		
        return gd;
	}
	
	
	
	/**				Create parameter dialog for Volume Transformation command
	 * 
	 * 			define as a class to implement  
	 */
	public boolean volume_transform () {//extends Parameter{
		// initialize volume transform specific parameters
		nTransform = 0;
		apply = new ArrayList<Boolean>();
		type = new ArrayList<String>();
		axis = new ArrayList<String>();
		value = new ArrayList<Double>();
		display = new ArrayList<Boolean>();
		// create user dialog
		GenericDialog gd = new OpmDialog("Transform Volume");
			styleDialog( gd );
			gd.setInsets(0, 10, 0);
			gd.addButton("+", new ActionListener() {
				public void actionPerformed(ActionEvent e) { addTransformation (Parameter.this, gd); }
			});
			gd.addToSameRow();		gd.addCheckbox("inverse", false);
			gd.addToSameRow(); 		gd.addButton("load", new ActionListener() { 
				public void actionPerformed(ActionEvent e) { load (gd); }
			});
			gd.addToSameRow(); 		gd.addButton("save", new ActionListener() { 
				public void actionPerformed(ActionEvent e) { save (Parameter.this, gd); }
			});
			gd.addToSameRow();		gd.addButton("save matrix", new ActionListener() { 
				public void actionPerformed(ActionEvent e) { saveMatrix (Parameter.this, gd); }
			});
			gd.addToSameRow();		gd.addImageChoice("", this.impInput.getTitle());
			gd.setInsets(20, 10, -10);
			gd.addMessage("apply      transform                         axis                 value");
			gd.enableYesNoCancel("Apply", "Apply in Reverse Order");
			gd.addHelp(Help.transform);
			gd.showDialog();
			if ( gd.wasCanceled() ) return false;
			if ( nTransform == 0 ) 	return false;
			doInverse = gd.getNextBoolean();
			impInput = gd.getNextImage();
			for (int i=0; i<nTransform; i++) {
				apply.add( gd.getNextBoolean() );
				type.add( gd.getNextChoice() );
				axis.add( gd.getNextChoice() );
				value.add( gd.getNextNumber() );
				display.add( gd.getNextBoolean() );
			}
			return true;
		}
	
		// member functions of volume transform parameter dialog
	
			/**			update transform parameter based on current dialog entries
			 * <br>		The transform dialog grows a row at a time, so the parameter lists are
			 * <br>		re-read from the dialog rather than tracked as the user edits.
			 *
			 * @param parameter	: parameter set to write the entries into
			 * @param dialog	: the transform dialog to read
			 */
			public void updateTransformParameter (
					Parameter parameter, 
					GenericDialog dialog
					) {
				parameter.doInverse = dialog.getNextBoolean();
				parameter.impInput = dialog.getNextImage();
				for (int i=0; i<parameter.nTransform; i++) {
					parameter.apply.add( dialog.getNextBoolean() );
					parameter.type.add( dialog.getNextChoice() );
					parameter.axis.add( dialog.getNextChoice() );
					parameter.value.add( dialog.getNextNumber() );
					parameter.display.add( dialog.getNextBoolean() );
				}
				dialog.resetCounters();
				
				List<double[][]> matrix_list = new ArrayList<double[][]>();
				for (int i=0; i<parameter.nTransform; i++) {
					if (!parameter.apply.get(i)) continue;
					double[][] matrix = Transform.parseTransformation (
							parameter.type.get(i), parameter.axis.get(i), parameter.value.get(i) );
					matrix_list.add(matrix);
				}
				parameter.deskewMatrix = Transform.affine ( matrix_list );
				if ( parameter.doInverse ) parameter.deskewMatrix = Transform.inverse( parameter.deskewMatrix );
			}
			
			/**			add a new transformation entry to dialog
			 *
			 * @param dialog	: the transform dialog to add a row to
			 * @param apply		: whether this row takes part in the combined matrix
			 * @param type		: translate, scale, rotate, shear_X, shear_Y or shear_Z
			 * @param axis		: axis the transformation acts on: X, Y or Z
			 * @param value		: transformation amount
			 * @param display	: show the intermediate result of this step
			 */
			public void addTransformation(
					GenericDialog dialog,
					boolean apply,
					String type,
					String axis,
					double value,
					boolean display
					) {
		   	 	dialog.addCheckbox("", apply);
		   	 	dialog.addToSameRow();
		   	 	dialog.addChoice("", typeChoices, type);
		   	 	dialog.addToSameRow();
		   	 	dialog.addChoice("  along", axisChoices, axis);
		   	 	dialog.addToSameRow();
		   	 	dialog.addNumericField("for", value, 3);
		   	 	dialog.addToSameRow();
		   	 	dialog.addCheckbox("show result", display);
		   	 	dialog.showDialog();
			}
			
			/**			add a new transformation entry to dialog, with default parameters
			 *
			 * @param parameter	: parameter set the new row is appended to
			 * @param dialog	: the transform dialog to add a row to
			 */
			public void addTransformation(
					Parameter parameter, 
					GenericDialog dialog
					) {
				if (parameter.nTransform != 0) {
					int nBox = dialog.getCheckboxes().size();
					Checkbox lastCheckbox = (Checkbox) dialog.getCheckboxes().elementAt(nBox-1);
					lastCheckbox.setState(false);
				}
				addTransformation ( dialog, true, typeChoices[0], axisChoices[0], 0.0, true );
		   	 	parameter.nTransform++;
			}
			
			/**			save transformation(s) to a csv file
			 * <br>		Saves the list of steps, not the combined matrix, so a saved file can be
			 * <br>		loaded back into the dialog and edited row by row.
			 *
			 * @param parameter	: parameter set holding the transformation list
			 * @param dialog	: the transform dialog, read for the current entries
			 */
			public void save (Parameter parameter, GenericDialog dialog) {
				updateTransformParameter( parameter, dialog );
				
				DefaultPrefService prefs = new DefaultPrefService();
				saveDir = prefs.get(String.class, "OPM-transform-saveDir", saveDir);
				if (null == saveDir) saveDir = IJ.getDir("home");
				SaveDialog sd = new SaveDialog(
						"save transformation(s)", saveDir + File.separator + "transformation.csv", ".csv");
				String csvPath = sd.getDirectory() + File.separator + sd.getFileName();
				if ( !IO.saveTransformationToFile ( parameter, csvPath ) ) return;
				prefs.put(String.class, "OPM-transform-saveDir", sd.getDirectory());
			}
			
			/**			load transformation(s) from a csv file
			 * <p>		append to the end of the current dialog
			 *
			 * @param dialog	: the transform dialog the loaded rows are appended to
			 */
			@SuppressWarnings("unchecked")
			public void load ( GenericDialog dialog ) {
				
				DefaultPrefService prefs = new DefaultPrefService();
				saveDir = prefs.get(String.class, "OPM-transform-saveDir", saveDir);
				if (null == saveDir) saveDir = IJ.getDir("home");
				OpenDialog od = new OpenDialog(
						"load transformation(s)", saveDir, "transformation.csv");
				String csvPath = od.getDirectory() + File.separator + od.getFileName();
				// load transform from file into parameter
				Map<String, Object> tranformMap = IO.loadTransformationFromFile ( csvPath );
				if ( null == tranformMap ) return;
				prefs.put(String.class, "OPM-transform-saveDir", od.getDirectory());
				// update transform to dialog
				List<Boolean> apply = (List<Boolean>) tranformMap.get("apply");
			 	List<String> type = (List<String>) tranformMap.get("type");
			 	List<String> axis = (List<String>) tranformMap.get("axis");
			 	List<Double> value = (List<Double>) tranformMap.get("value");
			 	List<Boolean> display = (List<Boolean>) tranformMap.get("display");
				for (int i=0; i<apply.size(); i++) {
					addTransformation( dialog, apply.get(i), type.get(i), axis.get(i), value.get(i), display.get(i) );
					nTransform++;
				}
			}
			
			/**			save current final matrix to a csv file
			 * <br>		The combined 4 x 4 matrix of every enabled row, in the same CSV format the
			 * <br>		deskew dialog reads, so it can be applied later without the step list.
			 *
			 * @param parameter	: parameter set holding the transformation list
			 * @param dialog	: the transform dialog, read for the current entries
			 */
			public void saveMatrix (Parameter parameter, GenericDialog dialog) {
				updateTransformParameter( parameter, dialog );
				
				if (parameter.doInverse) parameter.deskewMatrix = Transform.inverse ( parameter.deskewMatrix );
				
				DefaultPrefService prefs = new DefaultPrefService();
				saveDir = prefs.get(String.class, "OPM-transform-saveDir", saveDir);
				if (null == saveDir) saveDir = IJ.getDir("home");
				SaveDialog sd = new SaveDialog(
						"save (combined) transform matrix", saveDir + File.separator + "matrix.csv", ".csv");
				String csvPath = sd.getDirectory() + File.separator + sd.getFileName();
				if ( !IO.saveMatrixToFile ( parameter.deskewMatrix, csvPath ) ) return;
				prefs.put(String.class, "OPM-transform-saveDir", sd.getDirectory());
		
			}
			
			
			public void loadMatrix (Parameter parameter, GenericDialog dialog) {
				// TODO: add checkbox that disable either the param fields, or load matrix file fields
			}
	
			

	
	protected void parseDeskewParameter () {
		Parameter parameter = this;
		// check the setup file type, if unknown, do nothing
		if ( null == parameter.impInput ) { deskewmFile = loadSettingMessage; return; }
		double height = (double) parameter.impInput.getHeight();
		String fileType = getSetupFileType( parameter.deskewmFile );
		if (null == fileType) { deskewmFile = loadSettingMessage; return; }
		// load setup file, and update parameter(s)
		switch (fileType) {
		case "expParams":
			// try to load input parameter from file: xy pixel size, z step, angle, frame interval
			double[] expParams = IO.loadExperimentalParametersFromFile ( parameter.deskewmFile );
			if (null == expParams) { deskewmFile = loadSettingMessage; return; }
			parameter.xyPixelSize	= expParams[0];
			parameter.zStepSize		= expParams[1];
			parameter.opmAngle		= expParams[2];
			parameter.frameInterval = expParams[3];
			parameter.deskewMatrix = Transform.deskew ( parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, height );
			break;
		case "matrix":
			// try to load matrix from file
			double[][] deskew_matrix = IO.loadMatrixFromFile( parameter.deskewmFile );
			if (null == deskew_matrix) { deskewmFile = loadSettingMessage; return; }
			// try to calculate input parameter: z step, angle, and z-translate amount
			double[] matrix_inputParam = Transform.reverse_deskew ( deskew_matrix, height );
			if (null == matrix_inputParam) { deskewmFile = loadSettingMessage; return; }
			parameter.zStepSize = matrix_inputParam[0] * parameter.xyPixelSize;
			parameter.opmAngle = matrix_inputParam[1];
			deskew_matrix[2][3] = matrix_inputParam[2];
			parameter.deskewMatrix = deskew_matrix;
			break;
		case "transform":
			// try to load set of transformation from file
			Map<String, Object> transformation = IO.loadTransformationFromFile ( parameter.deskewmFile );
			if (null == transformation) { deskewmFile = loadSettingMessage; return; }
			// TODO: compute transformation matrix from the transformations
			// TODO: update input parameters: z step size, angle, and parameter.matrix
			break;
		}
	}
	
	
	protected void updateDeskewMatrix () {
		Parameter parameter = this;
		// check the setup file type, if unknown, do nothing
		if ( null == parameter.impInput || null == parameter.deskewMatrix ) return;
		double imageHeight = parameter.impInput.getHeight();
		double sin_theta = -parameter.deskewMatrix[2][1];
		double translate_z = Math.abs ( imageHeight * sin_theta );	// h * sin0
		parameter.deskewMatrix[2][3] = translate_z;
	}
	
	protected void updateAlignMatrix () {
		
	}
	
	protected void parseDeskewParameterBatch () {
		Parameter parameter = this;
		// check the setup file type, if unknown, do nothing
		String fileType = getSetupFileType( parameter.deskewmFile );
		parameter.deskewMatrix = null;
		if (null != fileType) {
			// load setup file, and update parameter(s)
			switch (fileType) {
			case "expParams":
				// try to load input parameter from file: xy pixel size, z step, angle, frame interval
				double[] expParams = IO.loadExperimentalParametersFromFile ( parameter.deskewmFile );
				if (null == expParams) break;
				parameter.xyPixelSize	= expParams[0];
				parameter.zStepSize		= expParams[1];
				parameter.opmAngle		= expParams[2];
				parameter.frameInterval = expParams[3];
				double imageHeight 		= expParams[5];
				parameter.deskewMatrix = Transform.deskew ( parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, imageHeight );
				break;
			case "matrix":
				// try to load matrix from file
				double[][] deskew_matrix = IO.loadMatrixFromFile( parameter.deskewmFile );
				if (null == deskew_matrix) break;
				parameter.deskewMatrix = deskew_matrix;
				break;
			}
		}
		if ( null == parameter.deskewMatrix ) parameter.deskewmFile = loadSettingMessage;
	}
	
	protected void parseDeskewParameterLive () {
		Parameter parameter = this;
		// parameters directly loaded from metadata file
		parameter.deskewMatrix = null;
		// try to load input parameter from file: xy pixel size, z step, angle, frame interval
		double[] expParams = IO.loadExperimentalParametersFromFile ( parameter.deskewmFile );
		if (null == expParams) return;
		parameter.xyPixelSize	= expParams[0];
		parameter.zStepSize		= expParams[1];
		parameter.opmAngle		= expParams[2];
		parameter.frameInterval = expParams[3];
		double imageHeight 		= expParams[5];
		parameter.deskewMatrix = Transform.deskew ( parameter.zStepSize, parameter.xyPixelSize, parameter.opmAngle, imageHeight );
		IO.displayMatrix(parameter.deskewMatrix);
	}
	
	
	/**		Recognise which kind of settings file the user has chosen
	 * <br>		The deskew dialog accepts three: the acquisition's own ExperimentalParameters.txt,
	 * <br>		a deskew matrix saved earlier, and a saved list of transformations.
	 *
	 * @param filePath	: path the user typed or picked
	 * <p>
	 * @return			: "expParams", "matrix", "transform", or null when unrecognised
	 */
	protected String getSetupFileType ( String filePath ) {
		File file = new File( filePath );
		if (null == file || !file.exists()) return null;
		String name = file.getName();
		if ( name.equals("ExperimentalParameters.txt") ) return "expParams";
		if ( name.toLowerCase().contains("matrix") ) return "matrix";
		if ( name.toLowerCase().contains("transform") ) return "transform";
		return null;
	}
	
	
	protected void parseProjectionParameter () {
		Parameter parameter = this;
		// prepare projection axis string list
		parameter.projAxes = new ArrayList<String>();
		if (parameter.projX) projAxes.add("X");
		if (parameter.projY) projAxes.add("Y");
		if (parameter.projZ) projAxes.add("Z");
		// prepare projection type string list
		parameter.projTypes = new ArrayList<String>();
		if (parameter.maxProj)	parameter.projTypes.add("max");
		if (parameter.avgProj)	parameter.projTypes.add("avg");
		if (parameter.minProj)	parameter.projTypes.add("min");
		if (parameter.sumProj)	parameter.projTypes.add("sum");
		if (parameter.medProj)	parameter.projTypes.add("med");
		if (parameter.stdProj)	parameter.projTypes.add("std");
		// check if neccessary to create projection image(s)
		if ( 0 == parameter.projAxes.size() || 0 == parameter.projTypes.size() )
			parameter.doProjection = false;
		else
			parameter.doProjection = true;
	}
	
	
	// parse alignment matrix?
	protected void parseAlignParameter () {
		Parameter parameter = this;
		/* The shared Channels block now decides which optical sources are emitted.  A selected
		 * matrix therefore remains meaningful even when the older single-image "channel option"
		 * is not "align with SIFT": combined Batch/Live and canonical OME-Zarr consume it by
		 * source label.  An absent/placeholder path still resolves to null below. */
		parameter.alignmentMatrices = AlignmentMatrixSet.load ( parameter.alignmFile );
		parameter.alignMatrix = parameter.alignmentMatrices == null
				? IO.loadMatrixFromFile ( parameter.alignmFile )
				: parameter.alignmentMatrices.legacyMatrix();
		if ( null == parameter.alignMatrix ) parameter.alignmFile = loadAlignMessage;
		return;
	}
	
	
	/**			Whether the selected output format includes TIFF results
	 * <p>		Everything the TIFF path produces - the deskewed stack, the per-projection
	 * 			sub-folders, the MIP movies - hangs off this. A Zarr-only run writes none of it.
	 */
	public boolean savesTiff () {
		return !FORMAT_ZARR.equals ( outputFormat );
	}

	/** Whether the selected output format includes one canonical OME-Zarr dataset. */
	public boolean savesZarr () {
		return !FORMAT_TIFF.equals ( outputFormat );
	}

	/**			Derive the individual output switches from the format dropdown
	 * <p>		{@code saveDeskewZarr} is no longer set by a checkbox of its own, and
	 * 			{@code doProjection} - which drives the TIFF projection sub-folders - is
	 * 			cleared for a Zarr-only run, whose projections live inside the dataset.
	 * 			Call after reading a dialog and before processing.
	 */
	public void applyOutputFormat () {
		if ( !isOutputFormat ( outputFormat ) ) outputFormat = FORMAT_ZARR;
		saveDeskewZarr = savesZarr();
		if ( !savesTiff() ) doProjection = false;
		/* "save projection views" only has a TIFF meaning: an OME-Zarr dataset carries all six
		 * projections inside itself whether or not the box is ticked. */
		if ( !saveProjectionViews ) doProjection = false;
	}

	/** Whether a stored or typed string is one of the offered output formats. */
	public static boolean isOutputFormat ( String value ) {
		for ( String option : OUTPUT_FORMATS ) if ( option.equals ( value ) ) return true;
		return false;
	}

	protected int[] parseZrange () {
		Parameter parameter = this;
		if (null == parameter.impInput) { parameter.zRangeStr = "1-end"; return null; }
		int numZ = parameter.impInput.getNSlices();
		if (1==numZ) numZ = parameter.impInput.getNFrames();
		String[] parts = parameter.zRangeStr.split("-");
		if ( parts.length != 2 ) { parameter.zRangeStr = "1-end"; return new int[]{1, numZ}; }
		int z1 = 1; int z2 = numZ;
		try {
			z1 = Integer.valueOf( parts[0] );
			if ( !parts[1].equals("end") ) z2 = Integer.valueOf( parts[1] );
		} catch ( Exception e ) {	// number foramt exception
			System.out.println( e.getMessage() );
		}
		z1 = Math.max(1, z1); z1 = Math.min(numZ, z1);
		z2 = Math.max(1, z2); z2 = Math.min(numZ, z2);
		if ( z1 >= z2 ) { int temp = z1; z1 = z2; z2 = temp; }
		if ( z2 == numZ ) parameter.zRangeStr = "" + z1 + "-end";
		else parameter.zRangeStr = "" + z1 + "-" + z2;
		return new int[] {z1, z2};
	}
	
	
	
	
}
