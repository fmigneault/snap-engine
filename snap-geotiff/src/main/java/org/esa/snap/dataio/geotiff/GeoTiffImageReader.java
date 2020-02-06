package org.esa.snap.dataio.geotiff;

import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageMetadata;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFRenderedImage;
import org.esa.snap.core.util.jai.JAIUtils;
import org.esa.snap.engine_utilities.util.FileSystemUtils;
import org.esa.snap.engine_utilities.util.FindChildFileVisitor;
import org.esa.snap.engine_utilities.util.NotRegularFileException;
import org.esa.snap.engine_utilities.util.ZipFileSystemBuilder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.media.jai.InterpolationNearest;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.CropDescriptor;
import javax.media.jai.operator.MosaicDescriptor;
import javax.media.jai.operator.TranslateDescriptor;
import java.awt.*;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Created by jcoravu on 22/11/2019.
 */
public class GeoTiffImageReader implements Closeable {

    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final byte FIRST_IMAGE = 0;

    private final TIFFImageReader imageReader;
    private final Closeable closeable;

    private RenderedImage swappedSubsampledImage;

    public GeoTiffImageReader(ImageInputStream imageInputStream) throws IOException {
        this.imageReader = findImageReader(imageInputStream);
        this.closeable = null;
    }

    public GeoTiffImageReader(File file) throws IOException {
        this.imageReader = buildImageReader(file);
        this.closeable = null;
    }

    public GeoTiffImageReader(InputStream inputStream, Closeable closeable) throws IOException {
        this.imageReader = buildImageReader(inputStream);
        this.closeable = closeable;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();

        close();
    }

    @Override
    public void close() {
        try {
            ImageInputStream imageInputStream = (ImageInputStream) this.imageReader.getInput();
            try {
                imageInputStream.close();
            } catch (IOException ignore) {
                // ignore
            }
        } finally {
            if (this.closeable != null) {
                try {
                    this.closeable.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }
    }

    public TIFFImageMetadata getImageMetadata() throws IOException {
        return (TIFFImageMetadata) this.imageReader.getImageMetadata(FIRST_IMAGE);
    }

    public Raster readRect(boolean isGlobalShifted180, int sourceOffsetX, int sourceOffsetY, int sourceStepX, int sourceStepY, int destOffsetX, int destOffsetY, int destWidth, int destHeight)
                           throws IOException {

        ImageReadParam readParam = this.imageReader.getDefaultReadParam();
        int subsamplingXOffset = sourceOffsetX % sourceStepX;
        int subsamplingYOffset = sourceOffsetY % sourceStepY;
        readParam.setSourceSubsampling(sourceStepX, sourceStepY, subsamplingXOffset, subsamplingYOffset);
        RenderedImage subsampledImage = this.imageReader.readAsRenderedImage(FIRST_IMAGE, readParam);
        Rectangle rectangle = new Rectangle(destOffsetX, destOffsetY, destWidth, destHeight);
        if (isGlobalShifted180) {
            if (this.swappedSubsampledImage == null) {
                this.swappedSubsampledImage = horizontalMosaic(getHalfImages(subsampledImage));
            }
            return this.swappedSubsampledImage.getData(rectangle);
        } else {
            return subsampledImage.getData(rectangle);
        }
    }

    public int getImageWidth() throws IOException {
        return this.imageReader.getWidth(FIRST_IMAGE);
    }

    public int getImageHeight() throws IOException {
        return this.imageReader.getHeight(FIRST_IMAGE);
    }

    public int getTileHeight() throws IOException {
        return this.imageReader.getTileHeight(FIRST_IMAGE);
    }

    public int getTileWidth() throws IOException {
        return this.imageReader.getTileWidth(FIRST_IMAGE);
    }

    public SampleModel getSampleModel() throws IOException {
        ImageReadParam readParam = this.imageReader.getDefaultReadParam();
        TIFFRenderedImage baseImage = (TIFFRenderedImage) this.imageReader.readAsRenderedImage(FIRST_IMAGE, readParam);
        return baseImage.getSampleModel();
    }

    public TIFFRenderedImage getBaseImage() throws IOException {
        ImageReadParam readParam = this.imageReader.getDefaultReadParam();
        return (TIFFRenderedImage) this.imageReader.readAsRenderedImage(FIRST_IMAGE, readParam);
    }

    public Dimension computePreferredTiling(int rasterWidth, int rasterHeight) throws IOException {
        int imageWidth = getImageWidth();
        int imageHeight = getImageHeight();
        int tileWidth = getTileWidth();
        int tileHeight = getTileHeight();
        boolean isBadTiling = (tileWidth <= 1 || tileHeight <= 1 || imageWidth == tileWidth || imageHeight == tileHeight);
        Dimension dimension;
        if (isBadTiling) {
            dimension = JAIUtils.computePreferredTileSize(rasterWidth, rasterHeight, 1);
        } else {
            if (tileWidth > rasterWidth) {
                tileWidth = rasterWidth;
            }
            if (tileHeight > rasterHeight) {
                tileHeight = rasterHeight;
            }
            dimension = new Dimension(tileWidth, tileHeight);
        }
        return dimension;
    }

    public Dimension validateSize(int metadataImageWidth, int metadataImageHeight) throws IOException {
        Dimension defaultBandSize = new Dimension(getImageWidth(), getImageHeight());
        if (defaultBandSize.width != metadataImageWidth) {
            throw new IllegalStateException("The width " + metadataImageWidth + " from the metadata file is not equal with the image width " + defaultBandSize.width + ".");
        }
        if (defaultBandSize.height != metadataImageHeight) {
            throw new IllegalStateException("The height " + metadataImageHeight + " from the metadata file is not equal with the image height " + defaultBandSize.height + ".");
        }
        return defaultBandSize;
    }

    public Dimension validateArea(Rectangle area) throws IOException {
        int imageWidth = getImageWidth();
        if ((area.x + area.width) > imageWidth) {
            throw new IllegalStateException("The coordinates are out of bounds: area.x="+area.x+", area.width="+area.width+", image.width=" + imageWidth);
        }
        int imageHeight = getImageHeight();
        if ((area.y + area.height) > imageHeight) {
            throw new IllegalStateException("The coordinates are out of bounds: area.y="+area.y+", area.height="+area.height+", image.height=" + imageHeight);
        }
        return new Dimension(imageWidth, imageHeight);
    }

    private static TIFFImageReader buildImageReader(Object sourceImage) throws IOException {
        TIFFImageReader imageReader = null;
        ImageInputStream imageInputStream = ImageIO.createImageInputStream(sourceImage);
        if (imageInputStream == null) {
            throw new NullPointerException("The image input stream is null for source image '" + sourceImage + "'.");
        }
        try {
            imageReader = findImageReader(imageInputStream);
        } finally {
            if (imageReader == null) {
                imageInputStream.close(); // failed to get the image reader
            }
        }
        return imageReader;
    }

    private static TIFFImageReader findImageReader(ImageInputStream imageInputStream) {
        Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(imageInputStream);
        while (imageReaders.hasNext()) {
            ImageReader reader = imageReaders.next();
            if (reader instanceof TIFFImageReader) {
                TIFFImageReader imageReader = (TIFFImageReader) reader;
                imageReader.setInput(imageInputStream);
                return imageReader;
            }
        }
        throw new IllegalStateException("GeoTiff imageReader not found.");
    }

    private RenderedImage[] getHalfImages(RenderedImage fullImage) {
        int xStart = 0;
        int yStart = 0;
        float width = (float) fullImage.getWidth() / 2;
        float height = fullImage.getHeight();
        final RenderedOp leftImage = CropDescriptor.create(fullImage, (float) xStart, (float) yStart, width, height, null);

        xStart = fullImage.getWidth() / 2;
        width = (float) (fullImage.getWidth() - xStart);
        final RenderedOp rightImage = CropDescriptor.create(fullImage, (float) xStart, (float) yStart, width, height, null);

        return new RenderedImage[]{leftImage, rightImage};
    }

    private static RenderedImage horizontalMosaic(RenderedImage[] halfImages) {
        final RenderedImage leftImage = halfImages[0];
        final RenderedImage rightImage = halfImages[1];
        // Translate the left image to shift it fullWidth/2 pixels to the right, and vice versa
        RenderedImage translatedLeftImage = TranslateDescriptor.create(leftImage, (float) leftImage.getWidth(), 0f, new InterpolationNearest(), null);
        RenderedImage translatedRightImage = TranslateDescriptor.create(rightImage, -1.0f * rightImage.getWidth(), 0f, new InterpolationNearest(), null);
        // Now mosaic the two images.
        return MosaicDescriptor.create(new RenderedImage[]{translatedRightImage, translatedLeftImage}, MosaicDescriptor.MOSAIC_TYPE_OVERLAY, null, null, null, null, null);
    }

    public static GeoTiffImageReader buildGeoTiffImageReader(Path productPath) throws IOException, IllegalAccessException, InstantiationException, InvocationTargetException {
        return buildGeoTiffImageReader(productPath, null);
    }

    public static GeoTiffImageReader buildGeoTiffImageReader(Path productPath, String childRelativePath)
                                            throws IOException, IllegalAccessException, InstantiationException, InvocationTargetException {

        if (Files.exists(productPath)) {
            // the product path exists
            if (Files.isDirectory(productPath)) {
                // the product path represents a folder
                Path child = productPath.resolve(childRelativePath);
                if (Files.exists(child)) {
                    if (Files.isRegularFile(child)) {
                        return new GeoTiffImageReader(child.toFile());
                    } else {
                        throw new NotRegularFileException("The product folder '"+productPath.toString()+"' does not contain the file '" + childRelativePath+"'.");
                    }
                } else {
                    throw new FileNotFoundException("The product folder '"+productPath.toString()+"' does not contain the path '" + childRelativePath+"'.");
                }
            } else if (Files.isRegularFile(productPath)) {
                // the product path represents a file
                if (productPath.getFileName().toString().toLowerCase().endsWith(GeoTiffProductReaderPlugIn.ZIP_FILE_EXTENSION)) {
                    if (childRelativePath == null) {
                        return buildGeoTiffImageReaderFromZipArchive(productPath);
                    } else {
                        return buildGeoTiffImageReaderFromZipArchive(productPath, childRelativePath);
                    }
                } else {
                    return new GeoTiffImageReader(productPath.toFile());
                }
            } else {
                // the product path does not represent a folder or a file
                throw new NotRegularFileException(productPath.toString());
            }
        } else {
            // the product path does not exist
            throw new FileNotFoundException("The product path '"+productPath+"' does not exist.");
        }
    }

    private static GeoTiffImageReader buildGeoTiffImageReaderFromZipArchive(Path productPath, String zipEntryPath)
                                                        throws IOException, IllegalAccessException, InstantiationException, InvocationTargetException {

        boolean success = false;
        FileSystem fileSystem = null;
        try {
            fileSystem = ZipFileSystemBuilder.newZipFileSystem(productPath);
            Iterator<Path> it = fileSystem.getRootDirectories().iterator();
            while (it.hasNext()) {
                Path zipArchiveRoot = it.next();
                Path entryPathToFind = ZipFileSystemBuilder.buildZipEntryPath(zipArchiveRoot, zipEntryPath);
                FindChildFileVisitor findChildFileVisitor = new FindChildFileVisitor(entryPathToFind);
                Files.walkFileTree(zipArchiveRoot, findChildFileVisitor);
                if (findChildFileVisitor.getExistingChildFile() != null) {
                    // the entry exists into the zip archive
                    GeoTiffImageReader geoTiffImageReader = buildGeoTiffImageReaderObject(findChildFileVisitor.getExistingChildFile(), fileSystem);
                    success = true;
                    return geoTiffImageReader;
                }
            } // end 'while (it.hasNext())'
            throw new FileNotFoundException("The zip archive '" + productPath.toString() + "' does not contain the file '" + zipEntryPath + "'.");
        } finally {
            if (fileSystem != null && !success) {
                fileSystem.close();
            }
        }
    }

    private static GeoTiffImageReader buildGeoTiffImageReaderFromZipArchive(Path productPath)
                                                        throws IOException, IllegalAccessException, InstantiationException, InvocationTargetException {

        boolean success = false;
        FileSystem fileSystem = null;
        try {
            fileSystem = ZipFileSystemBuilder.newZipFileSystem(productPath);
            TreeSet<String> filePaths = FileSystemUtils.listAllFilePaths(fileSystem);
            Iterator<String> itFileNames = filePaths.iterator();
            while (itFileNames.hasNext() && !success) {
                String filePath = itFileNames.next();
                boolean extensionMatches = Arrays.stream(GeoTiffProductReaderPlugIn.TIFF_FILE_EXTENSION).anyMatch(filePath.toLowerCase()::endsWith);
                if (extensionMatches) {
                    Path tiffImagePath = fileSystem.getPath(filePath);
                    GeoTiffImageReader geoTiffImageReader = buildGeoTiffImageReaderObject(tiffImagePath, fileSystem);
                    success = true;
                    return geoTiffImageReader;
                }
            }
            throw new IllegalArgumentException("The zip archive '" + productPath.toString() + "' does not contain an image. The item count is " + filePaths.size()+".");
        } finally {
            if (fileSystem != null && !success) {
                fileSystem.close();
            }
        }
    }

    private static GeoTiffImageReader buildGeoTiffImageReaderObject(Path tiffPath, Closeable closeable) throws IOException {
        boolean success = false;
        InputStream inputStream = Files.newInputStream(tiffPath);
        try {
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream, BUFFER_SIZE);
            InputStream inputStreamToReturn;
            if (tiffPath.getFileName().toString().endsWith(".gz")) {
                inputStreamToReturn = new GZIPInputStream(bufferedInputStream);
            } else {
                inputStreamToReturn = bufferedInputStream;
            }
            GeoTiffImageReader geoTiffImageReader = new GeoTiffImageReader(inputStreamToReturn, closeable);
            success = true;
            return geoTiffImageReader;
        } finally {
            if (!success) {
                inputStream.close();
            }
        }
    }
}
