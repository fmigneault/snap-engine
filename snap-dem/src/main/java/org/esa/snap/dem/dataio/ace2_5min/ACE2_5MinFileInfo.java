/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.snap.dem.dataio.ace2_5min;

import org.esa.snap.core.util.Guardian;
import org.esa.snap.core.util.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds information about a ACE file.
 *
 * @author Norman Fomferra
 */
public class ACE2_5MinFileInfo {

    private static final EastingNorthingParser PARSER = new EastingNorthingParser();

    private String _fileName;
    private long _fileSize;
    private float _easting;
    private float _northing;
    private float _pixelSizeX;
    private float _pixelSizeY;
    private int _width;
    private int _height;
    private float _noDataValue;

    private ACE2_5MinFileInfo() {
    }

    public String getFileName() {
        return _fileName;
    }

    public long getFileSize() {
        return _fileSize;
    }

    public float getEasting() {
        return _easting;
    }

    public float getNorthing() {
        return _northing;
    }

    public float getPixelSizeX() {
        return _pixelSizeX;
    }

    public float getPixelSizeY() {
        return _pixelSizeY;
    }

    public int getWidth() {
        return _width;
    }

    public int getHeight() {
        return _height;
    }

    public float getNoDataValue() {
        return _noDataValue;
    }

    public static ACE2_5MinFileInfo create(final Path file) throws IOException {
        return createFromDataFile(file);
    }

    private static ACE2_5MinFileInfo createFromDataFile(final Path dataFile) throws IOException {
        final ACE2_5MinFileInfo fileInfo = new ACE2_5MinFileInfo();
        fileInfo.setFromData(dataFile);
        return fileInfo;
    }

    private static ZipEntry getZipEntryIgnoreCase(final ZipFile zipFile, final String name) {
        final Enumeration enumeration = zipFile.entries();
        while (enumeration.hasMoreElements()) {
            final ZipEntry zipEntry = (ZipEntry) enumeration.nextElement();
            if (zipEntry.getName().equalsIgnoreCase(name)) {
                return zipEntry;
            }
        }
        return null;
    }

    private void setFromData(final Path dataPath) throws IOException {
        final String fileName = dataPath.getFileName().toString();
        final String ext = FileUtils.getExtension(fileName);
        if (ext != null && ext.equalsIgnoreCase(".zip")) {
            final String baseName = FileUtils.getFilenameWithoutExtension(fileName) + ".ACE2";
            final ZipFile zipFile = new ZipFile(dataPath.toFile());
            try {
                final ZipEntry zipEntry = getZipEntryIgnoreCase(zipFile, baseName);
                if (zipEntry == null) {
                    throw new IOException("Entry '" + baseName + "' not found in zip file.");
                }
                setFromData(baseName, zipEntry.getSize());
            } finally {
                zipFile.close();
            }
        } else {
            setFromData(fileName, dataPath.toFile().length());
        }
    }

    void setFromData(final String fileName, final long fileSize) throws IOException {
        _fileName = fileName;
        _fileSize = fileSize;

        final int[] eastingNorthing;
        try {
            eastingNorthing = parseEastingNorthing(fileName);
        } catch (ParseException e) {
            throw new IOException("Illegal file name format: " + fileName);
        }
        _easting = eastingNorthing[0];
        _northing = eastingNorthing[1];

        _width = (int) Math.sqrt(fileSize / 4);
        _height = _width;
        //if (_width * _height * 2L != fileSize) {
        ///    throw new IOException("Illegal file size: " + fileSize);
        //}

        _pixelSizeX = 300.0F / (60.0F * 60.0F);  // 300 arcsecond product
        _pixelSizeY = _pixelSizeX;

        _noDataValue = ACE2_5MinElevationModelDescriptor.NO_DATA_VALUE;
    }

    private static int[] parseEastingNorthing(final String text) throws ParseException {
        Guardian.assertNotNullOrEmpty("text", text);
        if (text.length() == 0) {
            return null;
        }
        return PARSER.parse(text);
    }

    private static class EastingNorthingParser {

        private static final int ILLEGAL_DIRECTION_VALUE = -999;

        private final int directionWest = 0;
        private final int directionEast = 1;
        private final int directionNorth = 2;
        private final int directionSouth = 3;

        private final int indexEasting = 0;
        private final int indexNorthing = 1;

        private String text;
        private int pos;
        private static final char EOF = (char) -1;

        private int[] parse(final String text) throws ParseException {
            initParser(text);
            return parseImpl();
        }

        private void initParser(final String text) {
            this.text = text;
            this.pos = -1;
        }

        private int[] parseImpl() throws ParseException {
            final int[] eastingNorthing = new int[]{ILLEGAL_DIRECTION_VALUE, ILLEGAL_DIRECTION_VALUE};
            parseDirectionValueAndAssign(eastingNorthing); // one per direction
            parseDirectionValueAndAssign(eastingNorthing); // one per direction
            validateThatValuesAreAssigned(eastingNorthing);
            validateCorrectSuffix();

            return eastingNorthing;
        }

        private void validateThatValuesAreAssigned(final int[] eastingNorthing) throws ParseException {
            if (eastingNorthing[indexEasting] == ILLEGAL_DIRECTION_VALUE) {
                throw new ParseException("Easting value not available.", -1);
            }
            if (eastingNorthing[indexNorthing] == ILLEGAL_DIRECTION_VALUE) {
                throw new ParseException("Northing value not available.", -1);
            }
        }

        private void validateCorrectSuffix() throws ParseException {
            final String suffix = text.substring(++pos);
            if (!suffix.matches("_5M.ACE2")) {
                throw new ParseException("Illegal string format.", pos);
            }
        }

        private void parseDirectionValueAndAssign(final int[] eastingNorthing) throws ParseException {
            int value = readNumber();
            final int direction = getDirection();
            value = correctValueByDirection(value, direction);
            assignValueByDirection(eastingNorthing, value, direction);
        }

        private void assignValueByDirection(final int[] eastingNorthing, final int value, final int direction) {
            if (isWest(direction) || isEast(direction)) {
                eastingNorthing[indexEasting] = value;
            } else {
                eastingNorthing[indexNorthing] = value;
            }
        }

        private int correctValueByDirection(int value, final int direction) throws ParseException {
            value *= (isWest(direction) || isSouth(direction)) ? -1 : +1;
            if (isWest(direction) && (value > 0 || value < -180)) {
                throw new ParseException(
                        "The value '" + value + "' for west direction is out of the range -180 ... 0.", pos);
            }
            if (isEast(direction) && (value < 0 || value > 180)) {
                throw new ParseException("The value '" + value + "' for east direction is out of the range 0 ... 180.",
                        pos);
            }
            if (isSouth(direction) && (value > 0 || value < -90)) {
                throw new ParseException(
                        "The value '" + value + "' for south direction is out of the range -90 ... 0.", pos);
            }
            if (isNorth(direction) && (value < 0 || value > 90)) {
                throw new ParseException("The value '" + value + "' for north direction is out of the range 0 ... 90.",
                        pos);
            }
            return value;
        }

        private boolean isNorth(final int direction) {
            return compareDirection(directionNorth, direction);
        }

        private boolean isEast(final int direction) {
            return compareDirection(directionEast, direction);
        }

        private boolean isSouth(final int direction) {
            return compareDirection(directionSouth, direction);
        }

        private boolean isWest(final int direction) {
            return compareDirection(directionWest, direction);
        }

        private static boolean compareDirection(final int expected, final int direction) {
            return expected == direction;
        }

        private int getDirection() throws ParseException {
            final char c = nextChar();
            if (c == 'w' || c == 'W') {
                return directionWest;
            }
            if (c == 'e' || c == 'E') {
                return directionEast;
            }
            if (c == 'n' || c == 'N') {
                return directionNorth;
            }
            if (c == 's' || c == 'S') {
                return directionSouth;
            }
            throw new ParseException("Illegal direction character. " + c, pos);
        }

        private int readNumber() throws ParseException {
            char c = nextChar();
            if (!Character.isDigit(c)) {
                throw new ParseException("Digit character expected.", pos);
            }
            int value = 0;
            while (Character.isDigit(c)) {
                value *= 10;
                value += (c - '0');
                c = nextChar();
            }
            pos--;
            return value;
        }

        private char nextChar() {
            pos++;
            return pos < text.length() ? text.charAt(pos) : EOF;
        }

        private EastingNorthingParser() {
        }

    }
}
