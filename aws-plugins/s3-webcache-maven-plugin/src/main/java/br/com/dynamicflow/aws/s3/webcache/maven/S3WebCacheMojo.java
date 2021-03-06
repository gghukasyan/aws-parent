package br.com.dynamicflow.aws.s3.webcache.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;

import javax.activation.MimetypesFileTypeMap;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.FileUtils;

import br.com.dynamicflow.aws.s3.webcache.util.WebCacheConfig;
import br.com.dynamicflow.aws.s3.webcache.util.WebCacheManager;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;

import edu.emory.mathcs.backport.java.util.Arrays;

/**
 * @prefix s3-webcache
 * @requiresProject true
 * @requiresOnline true
 * @goal upload
 * @phase prepare-package
 * @description Uploads static resources to a AWS S3 Bucket
 * 
 */
public class S3WebCacheMojo extends AbstractMojo {
	
	private static final String DIGEST_SHA512 = "sha512";

	private static final String DIGEST_SHA384 = "sha384";

	private static final String DIGEST_SHA256 = "sha256";

	private static final String DIGEST_SHA1 = "sha1";

	private static final String DIGEST_MD5 = "md5";

	private static final List<String> DIGEST_OPTIONS = Arrays.asList(new String[]{DIGEST_MD5,DIGEST_SHA1,DIGEST_SHA256,DIGEST_SHA384,DIGEST_SHA512});
	
	private static final String CONTENT_ENCODING_PLAIN = "plain";

	private static final String CONTENT_ENCODING_GZIP = "gzip";

	private static final List<String> CONTENT_ENCODING_OPTIONS = Arrays.asList(new String[]{CONTENT_ENCODING_PLAIN,CONTENT_ENCODING_GZIP});
	
	private static final int BUFFER_SIZE = 4096;
	
	public static final String S3_URL = "s3.amazonaws.com";
	
	private static final MimetypesFileTypeMap mimeMap = new MimetypesFileTypeMap();
	
	private static final SimpleDateFormat httpDateFormat;
	 
	static{
		httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		httpDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	
	/**
	 * @parameter property="accessKey"
	 */
	private String accessKey;
	
	/**
	 * @parameter property="secretKey"
	 */
	private String secretKey;
	
	/**
	 * @parameter property="bucketName" 
	 */
	private String bucketName;
	
	/**
	 * @parameter property="hostName"
	 */
	private String hostName;
	
	/**
	* The comma separated list of tokens to that will not be processed. 
	* By default excludes all files under WEB-INF and META-INF directories.
	* Note that you can use the Java Regular Expressions engine to
	* include and exclude specific pattern using the expression %regex[].
	* Hint: read the about (?!Pattern).
	*
	* @parameter
	*/
	private List<String> excludes;
	
	/**
	* The comma separated list of tokens that will br processed. 
	* By default contains the extensions: gif, jpg, tif, png, pdf, swf, eps, js and css.
	* Note that you can use the Java Regular Expressions engine 
	* to include and exclude specific pattern
	* using the expression %regex[].
	*
	* @parameter
	*/
	private List<String> includes;
	
	/**
	* The directory where the webapp is built.
	*
	* @parameter default-value="${project.build.directory}/${project.build.finalName}"
	* @required
	*/
	private File outputDirectory;
	
	/**
	* Single directory for extra files to include in the WAR. This is where
	* you place your JSP files.
	*
	* @parameter default-value="${basedir}/src/main/webapp"
	* @required
	*/
	private File inputDirectory;
	
	/**
	* Directory to encode files before uploading
	*
	* @parameter default-value="${project.build.directory}/s3-webcache/temp"
	* @required
	*/
	private File tmpDirectory;
	
	/**
	* Manifest File
	*
	* @parameter default-value="${project.build.directory}/${project.build.finalName}/WEB-INF/s3-webcache.xml"
	* @required
	*/
	private File manifestFile;
	
	/**
	* Content Encoding Type
	*
	* @parameter default-value="gzip"
	* @required
	*/
	private String contentEncoding;
	
	/**
	* Digest Type
	*
	* @parameter default-value="sha256"
	* @required
	*/
	private String digestType;
	
	public void execute() throws MojoExecutionException {
		getLog().info("tmpDirectory " + tmpDirectory.getPath());
		getLog().info("inputDirectory " + inputDirectory.getPath());
		getLog().info("outputDirectory " + outputDirectory.getPath());
		getLog().info("manifestFile " + manifestFile.getPath());
		getLog().info("includes " + includes);
		getLog().info("excludes " + excludes);
		
		if (hostName==null || hostName.length()==0) {
			hostName=bucketName+"."+S3_URL;
		}
		
		getLog().info("using hostName " + hostName);
		
		if (!contains(DIGEST_OPTIONS, digestType)) {
			throw new MojoExecutionException("digestType "+digestType+" must be in "+DIGEST_OPTIONS);
		}
		getLog().info("using digestType "+digestType);
		
		WebCacheConfig webCacheConfig = new WebCacheConfig(hostName);
		
		BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKey,secretKey);
		AmazonS3Client client = new AmazonS3Client(awsCredentials);
		
		try {
			getLog().info( "determining files that should be uploaded" );
			getLog().info("");
			List<String> fileNames = FileUtils.getFileNames(inputDirectory, convertToString(includes), convertToString(excludes), true, false);
			for (String fileName: fileNames) {
				processFile(client, webCacheConfig, new File(fileName));
			}
		} catch (IOException e) {
			throw new MojoExecutionException("cannot determine the files to be processed", e);
		}
		
		generateConfigManifest(webCacheConfig);
	}

	private void processFile(AmazonS3Client client, WebCacheConfig webCacheConfig, File file)
			throws MojoExecutionException {
		getLog().info("start processing file "+file.getPath()); 	
		
		File encodedFile = encodeFile(file);
		String contentType = mimeMap.getContentType(file);
		
		String digest = calculateDigest(encodedFile);
		
		ObjectMetadata objectMetadata = retrieveObjectMetadata(client, digest);
		
		if (objectMetadata != null && objectMetadata.getETag().equals(calculateETag(encodedFile))) {
			getLog().info("the object "+file.getName()+" stored at "+bucketName+" does not require update");
		} else {
			uploadFile(client, encodedFile, digest, contentType);
		}
		
		webCacheConfig.addToCachedFiles(file.getPath().substring(inputDirectory.getPath().length()),digest);
		
		getLog().info("finnish processing file "+file.getPath());
		getLog().info("");
	}

	private File encodeFile(File file) throws MojoExecutionException {
		getLog().info("contentEncoding file "+file.getPath()+" using "+contentEncoding);
		if (!tmpDirectory.exists() && !tmpDirectory.mkdirs()) {
			throw new MojoExecutionException("cannot create directory "+tmpDirectory);
		}
		
		File encodedFile = null;
		if (CONTENT_ENCODING_PLAIN.equalsIgnoreCase(contentEncoding)) {
			encodedFile = file;
		}
		else if (CONTENT_ENCODING_GZIP.equals(contentEncoding)) {
			FileInputStream fis = null;
			GZIPOutputStream gzipos = null;
			try {
				byte buffer[] = new byte[BUFFER_SIZE];
				encodedFile = File.createTempFile(file.getName()+"-",".tmp", tmpDirectory);
				fis = new FileInputStream(file);
				gzipos = new GZIPOutputStream(new FileOutputStream(encodedFile));
				int read = 0;
				do {
					read = fis.read(buffer, 0, buffer.length);
					if (read>0)
						gzipos.write(buffer, 0, read);
				} while (read>=0);
			} catch (Exception e) {
				throw new MojoExecutionException("could not process "+file.getName(),e);
			} finally {
				if (fis!= null)
					try {
						fis.close();
					} catch (IOException e) {
						throw new MojoExecutionException("could not process "+file.getName(),e);
					}
				if (gzipos!= null)
					try {
						gzipos.close();
					} catch (IOException e) {
						throw new MojoExecutionException("could not process "+encodedFile.getName(),e);
					}
			}
		}
		
		return encodedFile;
	}

	private void uploadFile(AmazonS3Client client, File file, String remoteFileName, String contentType) throws MojoExecutionException {
		getLog().info("uploading file "+file+" to "+bucketName);	
		try {
			getLog().info("content type for "+file.getName()+" is "+contentType);
			ObjectMetadata objectMetadata = new ObjectMetadata();
			objectMetadata.setContentLength(file.length());
			objectMetadata.setHeader("Content-Disposition", "filename="+file.getName());
			objectMetadata.setHeader("Cache-Control", "public, s-maxage=315360000, max-age=315360000");
			Calendar calendar = Calendar.getInstance();
			calendar.add(Calendar.YEAR, 10);
			objectMetadata.setHeader("Expires", httpDateFormat.format(calendar.getTime()));
			objectMetadata.setLastModified(new Date(file.lastModified()));
			if (!CONTENT_ENCODING_PLAIN.equalsIgnoreCase(contentEncoding)) {
				objectMetadata.setContentEncoding(contentEncoding.toLowerCase());
			}
			objectMetadata.setContentType(contentType);
			client.putObject(bucketName, remoteFileName, new FileInputStream(file), objectMetadata);			
		} catch (AmazonServiceException e) {
			throw new MojoExecutionException("could not upload file "+file.getName(),e);
		} catch (AmazonClientException e) {
			throw new MojoExecutionException("could not upload file "+file.getName(),e);
		} catch (FileNotFoundException e) {
			getLog().error(e);
		}
	}
	
	private ObjectMetadata retrieveObjectMetadata(AmazonS3Client client, String remoteFileName) throws MojoExecutionException {
		getLog().info("retrieving metadata for "+remoteFileName);
		ObjectMetadata objectMetadata = null;

		try {
			objectMetadata = client.getObjectMetadata(bucketName, remoteFileName);
			getLog().info( "  ETag: " + objectMetadata.getETag());
			getLog().info( "  ContentMD5: " + objectMetadata.getContentMD5());
			getLog().info( "  ContentType: " + objectMetadata.getContentType());
			getLog().info( "  CacheControl: " + objectMetadata.getCacheControl());
			getLog().info( "  ContentEncoding: " + objectMetadata.getContentEncoding());
			getLog().info( "  ContentDisposition: " + objectMetadata.getContentDisposition());
			getLog().info( "  ContentLength: " + objectMetadata.getContentLength());
			getLog().info( "  LastModified: " + objectMetadata.getLastModified());
		} catch (AmazonServiceException e) {
			getLog().info("  no object metadata found");
		} catch (AmazonClientException e) {
			throw new MojoExecutionException("  could not retrieve object metadata",e);
		}
		return objectMetadata;
	}

	private String calculateETag(File file) throws MojoExecutionException {
		String eTag = null;
		try {
			eTag = Hex.encodeHexString(DigestUtils.md5(new FileInputStream(file)));
		} catch (Exception e) {
			throw new MojoExecutionException("could not calculate ETag for "+file.getName(),e);
		} 
		getLog().info("eTag for "+file.getName()+" is "+eTag);
		return eTag;
	}
	
	private boolean contains(List<String> digestOptions, String search) {
		if (search == null) {
			throw new IllegalArgumentException("search cannot be null");
		}
		for (String item: digestOptions) {
			if (search.equalsIgnoreCase(item)) {
				return true;
			}
		}
		return false;
	}
	
	private String calculateDigest(File file) throws MojoExecutionException {
		String digest = null;
		try {
			if (DIGEST_MD5.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.md5(new FileInputStream(file)));
			} 
			else if (DIGEST_SHA1.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha(new FileInputStream(file)));
			}
			else if (DIGEST_SHA256.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha256(new FileInputStream(file)));
			}
			else if (DIGEST_SHA384.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha384(new FileInputStream(file)));
			} 
			else if (DIGEST_SHA512.equalsIgnoreCase(digestType)) {
				digest = Hex.encodeHexString(DigestUtils.sha512(new FileInputStream(file)));
			} 

		} catch (Exception e) {
			throw new MojoExecutionException("could not calculate digest for "+file.getName(),e);
		} 
		getLog().info("digest for "+file.getName()+" is "+digest);
		return digest;
	}
	
	private void generateConfigManifest(WebCacheConfig webCacheConfig) throws MojoExecutionException {
		WebCacheManager webCacheManager = new WebCacheManager();
		webCacheManager.persistConfig(webCacheConfig, manifestFile);
	}
	
	private String convertToString(List<String> list) {
		StringBuilder builder = new StringBuilder();
		int i = 0;
		for (Iterator<?> iterator = list.iterator(); iterator.hasNext(); i++) {
			if (i > 0)
				builder.append(", ");
			builder.append(iterator.next());
		}
		return builder.toString();
	}
}
