package de.embl.iclm;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import ij.IJ;
import ij.gui.Roi;
import ij.io.RoiDecoder;

public class IO {

	
	
	/**			Load OPM experiment parameters from txt format meta data file
	 * 
	 * @param filePath					: experiment parameter txt file full path: "...\ExperimentParameters.txt"
	 * <p>
	 * @return double[]					: { xy pixel size nm, z step nm, opm angle }
	 */
	public static double[] loadExperimentalParametersFromFile (
			String filePath
			) {
		double xyPixelSizeUm = Double.NaN;
		double galvoDUsizeUm = Double.NaN;
		double DUsPerStep = Double.NaN;
		double opmAngle = Double.NaN;
		double frameInterval = Double.NaN;
		String intervalString = null;
		double width = Double.NaN;
		double height = Double.NaN;
		String dimString = null;
		
		// read 
		String metadata = IJ.openAsString(filePath);
		if (null == metadata || metadata.startsWith("Error")) return null;
		String[] lines = metadata.split( "\\r?\\n|\\r" );
		for (String line : lines) {
			String[] parts = line.split(":");
			switch (parts[0]) {
				case "Tilt angle":
					opmAngle 		= Double.valueOf( parts[1] );
					break;
				case "um per galvo DU":
					galvoDUsizeUm 	= Double.valueOf( parts[1] );
					break;
				case "Galvo DUs per step":
					DUsPerStep 		= Double.valueOf( parts[1] );
					break;
				case "Pixel size at object /um":
					xyPixelSizeUm 	= Double.valueOf( parts[1] );
					break;
				case "Interval":
					intervalString = parts[1];
					break;
				case "Camera ROI X0, X1, Y0, Y1":
					dimString = parts[1];
					break;
			}
		}
		// check if xy pixel size, z step size, and OPM angle parsed correctly
		if (   Double.isNaN(xyPixelSizeUm) 
			|| Double.isNaN(galvoDUsizeUm) 
			|| Double.isNaN(DUsPerStep) 
			|| Double.isNaN(opmAngle))
			return null;
		// parse frame interval
		String[] intervalParts = intervalString.split(" ");
		frameInterval = Double.valueOf( intervalParts[1] );
		if ( intervalParts[2].toLowerCase().startsWith("min") ) frameInterval *= 60;
		if ( intervalParts[2].toLowerCase().startsWith("h") )	frameInterval *= 3600;
		// parse image dimension
		String[] dimParts = dimString.split(" ");
		width =  Math.abs ( Double.valueOf(dimParts[2]) - Double.valueOf(dimParts[1]) );
		height = Math.abs ( Double.valueOf(dimParts[4]) - Double.valueOf(dimParts[3]) );

		return new double[] { 1e3*xyPixelSizeUm, 1e3*galvoDUsizeUm*DUsPerStep, opmAngle, 
				frameInterval, width, height };
	}
	
	
	/**			display 2D or 3D transformation matrix
	 * 
	 * @param matrix		: 2D or 3D double array as value of transform matrix
	 */
	public static void displayMatrix (double[][] matrix) {
		if (null == matrix) {
			System.out.println("matrix is empty!");
			return;
		}
		int nRow = matrix.length; int nCol = matrix[0].length;
		for (int i=0; i<nRow; i++) {
			System.out.printf("\n\t");
			for (int j=0; j<nCol; j++) { System.out.printf("%f, ", matrix[i][j] ); }
		}
		System.out.printf("\n\n");
	}
	
	
	/**			Save transformation matrix (2D or 3D) to a csv file
	 * 
	 * @param matrix					: transformation matrix, 2D or 3D
	 * @param filePath					: csv file full path
	 * <p>
	 * @return boolean 					: whether save to csv file successful 
	 */
	public static boolean saveMatrixToFile (
			double[][] matrix, 
			String filePath
			) {
		if (null == matrix) return false;
		if ( !filePath.endsWith(".csv") ) filePath += ".csv";
		try {
			File csvFile = new File(filePath);
			csvFile.createNewFile();
			CSVWriter csvWriter = new CSVWriter(
					new FileWriter(csvFile, false), 
					CSVWriter.DEFAULT_SEPARATOR, 
					CSVWriter.NO_QUOTE_CHARACTER, 
					CSVWriter.DEFAULT_ESCAPE_CHARACTER, 
					CSVWriter.DEFAULT_LINE_END);
			for (int i=0; i<matrix.length; i++) {
				String[] currentLine = new String[matrix[i].length];
				for (int j=0; j<matrix[i].length; j++) {
					currentLine[j] = String.valueOf( matrix[i][j] );
				}
				csvWriter.writeNext( currentLine );
			}
			csvWriter.close();
		} catch (IOException e) {
			System.out.println( e.getMessage() );
			return false;
		}
		return true;
	}
	
	
	/**			Load transformation matrix (2D or 3D) from csv file
	 * 
	 * @param filePath					: csv file full path
	 * <p>
	 * @return double[][]				: transformation matrix, 2D or 3D
	 */
	public static double[][] loadMatrixFromFile (
			String filePath
			) {
		if (null == filePath) return null;
		File matrixFile = new File(filePath);
		if ( !matrixFile.exists() ) return null;
		/* A multi-channel alignment file is still accepted by old call sites.  They receive
		 * its first non-reference transform, while the Batch/Live paths use
		 * AlignmentMatrixSet directly and retain every source-specific matrix. */
		try {
			BufferedReader probe = new BufferedReader(new FileReader(matrixFile));
			String first = probe.readLine();
			probe.close();
			if (first != null && first.trim().equals(AlignmentMatrixSet.HEADER)) {
				AlignmentMatrixSet set = AlignmentMatrixSet.load(filePath);
				return set == null ? null : set.legacyMatrix();
			}
		} catch (IOException ignored) { return null; }
		double[][] matrix = null; // [4][4] for 3D affine; [2][3] for 2D alignment;
	 	try {
	 		List<String[]> values = new ArrayList<String[]>();
	 		int nCol = 0;	int nRow = 0;
	 		String[] currentLine = null;
	 		// open the csv file and read lines into list
			CSVReader reader = new CSVReader( new FileReader(filePath) );
			while ((currentLine = reader.readNext()) != null) {
				values.add(currentLine);
				nCol = Math.max( nCol, currentLine.length );
			}
			reader.close();
			// convert list of String array into matrix as double[][]
			nRow = values.size();
			matrix = new double[nRow][nCol];
			for (int i=0; i<nRow; i++) {
				for (int j=0; j<nCol; j++) {
					matrix[i][j] = Double.valueOf( values.get(i)[j] );
				}
			}
		} catch ( Exception e ) {
			System.out.println( e.getMessage() );
			return null;
		}
	 	if ( 0 == matrix[0][0] || 0 == matrix[1][1]) return null;
	 	return matrix;
	}
	
	
	/**			Save transformation(s) to a csv file
	 * <br>		each transformation as a new line, and is a sequence of readable parameter values
	 * 
	 * @param parameter					: Parameter object, stores the transformation(s)
	 * @param filePath					: csv file full path
	 * <p>
	 * @return boolean 					: whether save to csv file successful
	 */
	public static boolean saveTransformationToFile (
			Parameter parameter,
			String filePath
			) {
		if (null == parameter) return false;
		if ( !filePath.endsWith(".csv") ) filePath += ".csv";
		try {
			File csvFile = new File(filePath);
			csvFile.createNewFile();
			CSVWriter csvWriter = new CSVWriter(new FileWriter(csvFile, false));
			for (int i=0; i<parameter.nTransform; i++) {
				String[] currentLine = new String[5];
				currentLine[0] = String.valueOf( parameter.apply.get(i) );
				currentLine[1] = parameter.type.get(i);
				currentLine[2] = parameter.axis.get(i);
				currentLine[3] = String.valueOf( parameter.value.get(i) );
				currentLine[4] = String.valueOf( parameter.display.get(i) );
				csvWriter.writeNext( currentLine );
			}
			csvWriter.close();
		} catch (IOException e) {
			System.out.println( e.getMessage() );
			return false;
		}
		return true;
	}
	
	
	/**			Load transformation(s) from csv file
	 * <br>		read transformation(s) parameter and value from csv file
	 * 
	 * @param filePath					: csv file full path
	 * <p>
	 * @return Map<String, Object>		: a HashMap<String, Object> with parameter names as keys
	 */
	public static Map<String, Object> loadTransformationFromFile (
			String filePath
			) {
		if (null == filePath) return null;
		Map<String, Object> transformMap = null;
	 	try {
			CSVReader reader = new CSVReader( new FileReader(filePath) );
			List<Boolean> apply = new ArrayList<Boolean>();
		 	List<String> type = new ArrayList<String>();
		 	List<String> axis = new ArrayList<String>();
		 	List<Double> value = new ArrayList<Double>();
		 	List<Boolean> display = new ArrayList<Boolean>();
			String[] currentLine = null;
			while ((currentLine = reader.readNext()) != null) {
				apply.add( Boolean.valueOf( currentLine[0] ) );
				type.add( currentLine[1] );
				axis.add( currentLine[2] );
				value.add( Double.valueOf( currentLine[3] ) );
				display.add( Boolean.valueOf( currentLine[4] ) );
			}
			reader.close();
			transformMap = new HashMap<String, Object>();
			transformMap.put("apply", apply);
			transformMap.put("type", type);
			transformMap.put("axis", axis);
			transformMap.put("value", value);
			transformMap.put("display", display);
		} catch ( Exception e ) {
			System.out.println( e.getMessage() );
		}
		return transformMap;
	}
	
	
	/** Opens a single .roi file or a ZIP-compressed set of ROIs.
     *  Returns 'true' if the operation was succesful.
     */
    public static Roi[] loadRoiFromFile ( String path ) {
        if ( path == null || path.equals("") ) return null;
        if ( path.endsWith(".zip") ) {
            return loadRoiFromZipFile ( path );
        } else if ( path.endsWith(".roi") ) {
        	return new Roi[] { RoiDecoder.open(path) };
        } else {
        	return null;
        }
    }
    public static Roi[] loadRoiFromZipFile( String path ) {
        ZipInputStream in = null;
        ByteArrayOutputStream out = null;
        int nROI = 0;
        List<Roi> roiList = new ArrayList<Roi>();
        try {
            in = new ZipInputStream( new FileInputStream(path) );
            byte[] buf = new byte[1024];
            int len;
            ZipEntry entry = in.getNextEntry();
            while ( entry!=null ) {
                String name = entry.getName();
                if (name.endsWith(".roi")) {
                    out = new ByteArrayOutputStream();
                    while ((len = in.read(buf)) > 0)
                        out.write(buf, 0, len);
                    out.close();
                    byte[] bytes = out.toByteArray();
                    RoiDecoder rd = new RoiDecoder(bytes, name);
                    Roi roi = rd.getRoi();
                    if (roi!=null) {
                    	roiList.add( roi );
                        nROI++;
                    }
                }
                entry = in.getNextEntry();
            }
            in.close();
        } catch ( Exception e ) {
            System.out.println( e.getMessage() );
        } finally {
            if (in!=null)
                try {in.close();} catch (IOException e) {}
            if (out!=null)
                try {out.close();} catch (IOException e) {}
        }
        if ( nROI==0 ) return null;
        return roiList.toArray(new Roi[roiList.size()]);
    }
    
}
