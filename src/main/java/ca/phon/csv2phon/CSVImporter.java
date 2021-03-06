/*
 * Copyright (C) 2012-2018 Gregory Hedlund
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *    http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.phon.csv2phon;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import au.com.bytecode.opencsv.CSVReader;
import ca.phon.csv2phon.io.ColumnMapType;
import ca.phon.csv2phon.io.FileType;
import ca.phon.csv2phon.io.ImportDescriptionType;
import ca.phon.csv2phon.io.ParticipantType;
import ca.phon.extensions.UnvalidatedValue;
import ca.phon.fontconverter.TranscriptConverter;
import ca.phon.ipa.IPATranscript;
import ca.phon.ipa.IPATranscriptBuilder;
import ca.phon.ipa.alignment.PhoneAligner;
import ca.phon.ipa.alignment.PhoneMap;
import ca.phon.orthography.Orthography;
import ca.phon.project.Project;
import ca.phon.session.Group;
import ca.phon.session.MediaSegment;
import ca.phon.session.MediaUnit;
import ca.phon.session.Participant;
import ca.phon.session.ParticipantRole;
import ca.phon.session.Record;
import ca.phon.session.Session;
import ca.phon.session.SessionFactory;
import ca.phon.session.SystemTierType;
import ca.phon.session.Tier;
import ca.phon.session.TierDescription;
import ca.phon.session.TierString;
import ca.phon.session.TierViewItem;
import ca.phon.session.format.MediaSegmentFormatter;
import ca.phon.syllabifier.Syllabifier;
import ca.phon.syllabifier.SyllabifierLibrary;
import ca.phon.util.Language;
import ca.phon.util.OSInfo;

/**
 * Reads in the XML description of a CSV import and performs the import.
 * 
 *
 */
public class CSVImporter {
	
	private final static Logger LOGGER = Logger
			.getLogger(CSVImporter.class.getName());
	
	/** The import description */
	private ImportDescriptionType importDescription;
	
	/** The project we are importing into */
	private Project project;
	
	/** Directory where files are located */
	private String base;
	
	private String fileEncoding = "UTF-8";
	
	private char textDelimChar = '"';
	
	private char fieldDelimChar = ',';
	
	/**
	 * Constructor.
	 */
	public CSVImporter(String baseDir, ImportDescriptionType importDesc, Project project) {
		super();
		
		this.importDescription = importDesc;
		this.project = project;
		this.base = baseDir;
	}
	
	public void setFileEncoding(String charset) {
		this.fileEncoding = charset;
	}
	
	public String getFileEncoding() {
		return this.fileEncoding;
	}
	
	public char getTextDelimChar() {
		return textDelimChar;
	}

	public void setTextDelimChar(char textDelimChar) {
		this.textDelimChar = textDelimChar;
	}

	public char getFieldDelimChar() {
		return fieldDelimChar;
	}

	public void setFieldDelimChar(char fieldDelimChar) {
		this.fieldDelimChar = fieldDelimChar;
	}

	/**
	 * Begin import of specified files.
	 */
	public void performImport() {
		// print some info messages
		LOGGER.info("Importing files from directory '" + base + '"');
		
		for(FileType ft:importDescription.getFile()) {
			if(ft.isImport()) {
				try {
					LOGGER.info("Importing file '.../" + ft.getLocation() + "'");
					importFile(ft);
				} catch (IOException e) {
					LOGGER.log(Level.SEVERE, e.getLocalizedMessage(), e);
				}
			}
		}
		
		LOGGER.info("Import finished.");
	}
	
	public void importFile(FileType fileInfo) 
		throws IOException {
		// first try relative path from base
//		String base = importDescription.getBase();
		String location = fileInfo.getLocation();
		
		// check if location is an absolute path
		boolean absolute = false;
		if(OSInfo.isWindows()) {
			if(location.matches("[A-Z]:\\\\.*"))
				absolute = true;
		} else {
			if(location.startsWith("/"))
				absolute = true;
		}
		
		File csvFile = null;
		if(absolute)
			csvFile = new File(location);
		else
			csvFile = new File(base, location);
		
		if(!csvFile.exists()) {
			// throw an exception
			throw new FileNotFoundException("'" + 
					csvFile.getAbsolutePath() + "' not found, check the 'base' attribute of the csvimport element.");
		}
		
		final InputStreamReader csvInputReader = new InputStreamReader(new FileInputStream(csvFile), fileEncoding);
		// read in csv file
		final CSVReader reader = new CSVReader(csvInputReader, fieldDelimChar, textDelimChar);
		
		// create a new transcript in the project 
		// with the specified corpus and session name
		final String corpus = importDescription.getCorpus();
		final String session = fileInfo.getSession();
		if(!project.getCorpora().contains(corpus)) {
			LOGGER.info("Creating corpus '" + corpus + "'");
			project.addCorpus(corpus, "");
		}
		
		final SessionFactory factory = SessionFactory.newFactory();
		
		final Session t = project.createSessionFromTemplate(corpus, session);
		if(t.getRecordCount() > 0) t.removeRecord(0);
		
		if(fileInfo.getDate() != null) {
			final DateTimeFormatter dateFormatter = 
					DateTimeFormatter.ofPattern("yyyy-MM-dd");
			LocalDate sessionDate = 
					LocalDate.from(dateFormatter.parse(fileInfo.getDate()));
			t.setDate(sessionDate);
		}
		
		// add participants
		for(ParticipantType pt:importDescription.getParticipant()) {
			Participant newPart = CSVParticipantUtil.copyXmlParticipant(factory, pt, t.getDate());
			t.addParticipant(newPart);
		}
		
		if(fileInfo.getMedia() != null) {
			t.setMediaLocation(fileInfo.getMedia());
		}
		// set media file and date
		String[] colLine = reader.readNext();
		
		// create deptier descriptions as necessary
		for(String columnName:colLine) {
			ColumnMapType colmap = getColumnMap(columnName);
			if(colmap != null) {
				String tierName = colmap.getPhontier();
				if(tierName.equalsIgnoreCase("Don't import")) continue;
				
				if(!SystemTierType.isSystemTier(tierName) && !tierName.equalsIgnoreCase("Speaker:Name")) {
					final TierDescription tierDesc =
							factory.createTierDescription(tierName, colmap.isGrouped(), TierString.class);
					t.addUserTier(tierDesc);
					
					final TierViewItem tvi = factory.createTierViewItem(tierName);
					final List<TierViewItem> tierView = new ArrayList<>(t.getTierView());
					tierView.add(tvi);
					t.setTierView(tierView);
				}
			}
		}
		
		int createdParticipant = 0;
		String[] currentRow = null;
		while((currentRow = reader.readNext()) != null) {
			
			// add a new record to the transcript
			Record utt = factory.createRecord();
			t.addRecord(utt);
			
			for(int colIdx = 0; colIdx < colLine.length; colIdx++) {
				String csvcol = colLine[colIdx];
				String rowval = currentRow[colIdx];
				
				ColumnMapType colmap = getColumnMap(csvcol);
				if(colmap == null) {
					// print warning and continue
					LOGGER.warning("No column map for csv column '" + csvcol + "'");
					continue;
				}

				// convert if necessary
				TranscriptConverter tc = null;
				if(colmap.getFilter() != null && colmap.getFilter().length() > 0) {
					tc = TranscriptConverter.getInstanceOf(colmap.getFilter());
					if(tc == null) {
						LOGGER.warning("Could not find transcript converter '" + colmap.getFilter() + "'");
					}
				}
				
				String phontier = colmap.getPhontier().trim();
				if(phontier.equalsIgnoreCase("Don't Import")) {
					continue;
				}

				// do data pre-formatting if required
				if(colmap.getScript() != null) {
					// TODO: create a new javascript context and run the given script
				}

				// handle participant tier
				if(phontier.equals("Speaker:Name")) {

					// look for the participant in the transcript
					Participant speaker = null;
					for(Participant p:t.getParticipants()) {
						if(p.toString().equals(rowval)) {
							speaker = p;
							break;
						}
					}

					// if not found in the transcript, find the
					// participant info in the import description
					// add add the participant
					if(speaker == null) {
						speaker = factory.createParticipant();
						speaker.setName(rowval);
						speaker.setRole(ParticipantRole.PARTICIPANT);
						
						String id = "PA" + (createdParticipant > 0 ? createdParticipant : "R");
						++createdParticipant;
						speaker.setId(id);
						
						t.addParticipant(speaker);
					}

					utt.setSpeaker(speaker);
				} else {
					if(colmap.isGrouped() == null) colmap.setGrouped(true);
					// convert rowval into a list of group values
					List<String> rowVals = new ArrayList<String>();
					if(colmap.isGrouped() && rowval.startsWith("[") && rowval.endsWith("]")) {
						String[] splitRow = rowval.split("\\[");
						for(int i = 1; i < splitRow.length; i++) {
							String splitVal = splitRow[i];
							splitVal = splitVal.replaceAll("\\]", "");
							rowVals.add(splitVal);
						}
					} else {
						rowVals.add(rowval);
					}
					
					final SystemTierType systemTier = SystemTierType.tierFromString(phontier);
					if(systemTier != null) {
						if(systemTier == SystemTierType.Orthography) {
							final Tier<Orthography> orthoTier = utt.getOrthography();
							for(String grpVal:rowVals) {
								try {
									final Orthography ortho = Orthography.parseOrthography(grpVal);
									orthoTier.addGroup(ortho);
								} catch (ParseException e) {
									final Orthography ortho = new Orthography();
									final UnvalidatedValue uv = new UnvalidatedValue(grpVal, e);
									ortho.putExtension(UnvalidatedValue.class, uv);
									orthoTier.addGroup(ortho);
								}
							}
						} else if(systemTier == SystemTierType.IPATarget 
								|| systemTier == SystemTierType.IPAActual) {
							final Tier<IPATranscript> ipaTier = 
									(systemTier == SystemTierType.IPATarget ? utt.getIPATarget() : utt.getIPAActual());
							for(String grpVal:rowVals) {
								if(tc != null) {
									grpVal = tc.convert(grpVal);
								}
								grpVal = grpVal.trim();
								final IPATranscript ipa = (new IPATranscriptBuilder()).append(grpVal).toIPATranscript();
								ipaTier.addGroup(ipa);
							}
						} else if(systemTier == SystemTierType.Notes) {
							utt.getNotes().addGroup(new TierString(rowval));
						} else if(systemTier == SystemTierType.Segment) {
							final MediaSegmentFormatter segmentFormatter = new MediaSegmentFormatter();
							MediaSegment segment = factory.createMediaSegment();
							segment.setStartValue(0.0f);
							segment.setEndValue(0.0f);
							segment.setUnitType(MediaUnit.Millisecond);
							try {
								segment = segmentFormatter.parse(rowval);
							} catch (ParseException e) {
								LOGGER.log(Level.SEVERE,
										e.getLocalizedMessage(), e);
							}
							utt.getSegment().addGroup(segment);
						}
					} else {
						Tier<TierString> tier = utt.getTier(phontier, TierString.class);
						if(tier == null) {
							tier = factory.createTier(phontier, TierString.class, colmap.isGrouped());
							utt.putTier(tier);
						}
						
						if(tier.isGrouped()) {
							for(String grpVal:rowVals) {
								tier.addGroup(new TierString(grpVal));
							}
						} else {
							tier.setGroup(0, new TierString(rowval));
						}
					}
				}
			} // end for(colIdx)
			
			// do syllabification + alignment if necessary
			ColumnMapType targetMapping = getPhonColumnMap(SystemTierType.IPATarget.getName());
			ColumnMapType actualMapping = getPhonColumnMap(SystemTierType.IPAActual.getName());
			if(targetMapping != null && actualMapping != null) {
				
				final SyllabifierLibrary library = SyllabifierLibrary.getInstance();
				
				String targetLangName = targetMapping.getSyllabifier();
				if(targetLangName == null) {
					targetLangName = SyllabifierLibrary.getInstance().defaultSyllabifierLanguage().toString();
				}
				final Language targetLang = Language.parseLanguage(targetLangName);
				
				String actualLangName = targetMapping.getSyllabifier();
				if(actualLangName == null) {
					actualLangName = SyllabifierLibrary.getInstance().defaultSyllabifierLanguage().toString();
				}
				final Language actualLang = Language.parseLanguage(actualLangName);
				
				final PhoneAligner aligner = new PhoneAligner();
				
				Syllabifier targetSyllabifier = library.getSyllabifierForLanguage(targetLang);
				Syllabifier actualSyllabifier = library.getSyllabifierForLanguage(actualLang);
				
				for(int i = 0; i < utt.numberOfGroups(); i++) {
					final Group grp = utt.getGroup(i);
					final IPATranscript targetRep = grp.getIPATarget();
					if(targetSyllabifier != null) {
						targetSyllabifier.syllabify(targetRep.toList());
					}
					
					final IPATranscript actualRep = grp.getIPAActual();
					if(actualSyllabifier != null) {
						actualSyllabifier.syllabify(actualRep.toList());
					}
					
					PhoneMap pm = aligner.calculatePhoneMap(targetRep, actualRep);
					grp.setPhoneAlignment(pm);
				}
				
			}
		} // end while(currentRow)
		
		reader.close();
		
		// save transcript
		final UUID writeLock = project.getSessionWriteLock(t);
		if(writeLock != null) {
			project.saveSession(t, writeLock);
			project.releaseSessionWriteLock(t, writeLock);
		}
	}
	
	/**
	 * Returns the column mapping for the given csvcolumn.
	 */
	private ColumnMapType getColumnMap(String csvcol) {
		ColumnMapType retVal = null;
		
		for(ColumnMapType cmt:importDescription.getColumnmap()) {
			if(cmt.getCsvcolumn().equals(csvcol)) {
				retVal = cmt;
				break;
			}
		}
		
		return retVal;
	}
	
	/**
	 * Returns the column mapping for the given phon column.
	 */
	private ColumnMapType getPhonColumnMap(String phoncol) {
		ColumnMapType retVal = null;
		
		for(ColumnMapType cmt:importDescription.getColumnmap()) {
			if(cmt.getPhontier().equals(phoncol)) {
				retVal = cmt;
				break;
			}
		}
		
		return retVal;
	}

	/**
	 * Returns the participant with the given name
	*/
	private ParticipantType getParticipant(String partName) {
		ParticipantType retVal = null;

		for(ParticipantType part:importDescription.getParticipant()) {
			if(part.getName().equals(partName)) {
				retVal = part;
				break;
			}
		}

		return retVal;
	}

}
