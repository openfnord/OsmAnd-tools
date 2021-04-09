package net.osmand.server.controllers.pub;

import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.google.gson.Gson;

import net.osmand.server.api.repo.DeviceSubscriptionsRepository;
import net.osmand.server.api.repo.DeviceSubscriptionsRepository.SupporterDeviceSubscription;
import net.osmand.server.api.repo.PremiumUserDevicesRepository;
import net.osmand.server.api.repo.PremiumUserDevicesRepository.PremiumUserDevice;
import net.osmand.server.api.repo.PremiumUserFilesRepository;
import net.osmand.server.api.repo.PremiumUserFilesRepository.UserFile;
import net.osmand.server.api.repo.PremiumUserFilesRepository.UserFileNoData;
import net.osmand.server.api.repo.PremiumUsersRepository;
import net.osmand.server.api.repo.PremiumUsersRepository.PremiumUser;
import net.osmand.server.api.services.EmailSenderService;
import net.osmand.util.Algorithms;

@RestController
@RequestMapping("/userdata")
public class PremiumUsersController {

	private static final int ERROR_CODE_PREMIUM_USERS = 100;
	private static final int ERROR_CODE_EMAIL_IS_INVALID = 1 + ERROR_CODE_PREMIUM_USERS;
	private static final int ERROR_CODE_NO_VALID_SUBSCRIPTION = 2 + ERROR_CODE_PREMIUM_USERS;
	private static final int ERROR_CODE_USER_IS_NOT_REGISTERED = 3 + ERROR_CODE_PREMIUM_USERS;
	private static final int ERROR_CODE_TOKEN_IS_NOT_VALID_OR_EXPIRED = 4 + ERROR_CODE_PREMIUM_USERS;
	private static final int ERROR_CODE_PROVIDED_TOKEN_IS_NOT_VALID = 5 + ERROR_CODE_PREMIUM_USERS;
	private static final int ERROR_CODE_FILE_NOT_AVAILABLE = 6 + ERROR_CODE_PREMIUM_USERS;
	private static final int ERROR_CODE_GZIP_ONLY_SUPPORTED_UPLOAD = 7 + ERROR_CODE_PREMIUM_USERS;

	protected static final Log LOG = LogFactory.getLog(PremiumUsersController.class);

	Gson gson = new Gson();
	
	@Autowired
    protected DeviceSubscriptionsRepository subscriptionsRepo;
	
    @Autowired
    protected PremiumUsersRepository usersRepository;
    
    @Autowired
    protected PremiumUserFilesRepository filesRepository;
    
    @Autowired
    protected PremiumUserDevicesRepository devicesRepository;
    
    @Autowired
	EmailSenderService emailSender;
    
//    @PersistenceContext 
//    protected EntityManager entityManager;
    

    protected ResponseEntity<String> error(int errorCode, String message) {
    	Map<String, Object> mp = new TreeMap<String, Object>();
    	mp.put("errorCode", errorCode);
    	mp.put("message", message);
    	return ResponseEntity.badRequest().body(gson.toJson(Collections.singletonMap("error", mp)));
    }
    
    
    private ResponseEntity<String> ok() {
    	return ResponseEntity.ok(gson.toJson(Collections.singletonMap("status", "ok")));
    }
    
	private boolean checkOrderIdPremium(String orderid) {
		if (Algorithms.isEmpty(orderid)) {
			return false;
		}
		boolean premiumPresent = false;
		List<SupporterDeviceSubscription> lst = subscriptionsRepo.findByOrderId(orderid);
		for (SupporterDeviceSubscription s : lst) {
			// s.sku could be checked for premium
			if (s.valid != null && s.valid.booleanValue()) {
				premiumPresent = true;
				break;
			}
		}
		return premiumPresent;
	}
	
    
	private PremiumUserDevice checkToken(int deviceId, String accessToken) {
		PremiumUserDevice d = devicesRepository.findById(deviceId);
		if (d != null && Algorithms.stringsEqual(d.accesstoken, accessToken)) {
			return d;
		}
		return null;
	}
	
	
	@PostMapping(value = "/user-register")
	@ResponseBody
	public ResponseEntity<String> userRegister(@RequestParam(name = "email", required = true) String email, 
			@RequestParam(name = "deviceid", required = true) String deviceId,
			@RequestParam(name = "orderid", required = false) String orderid)
			throws IOException {
		PremiumUser pu = usersRepository.findByEmail(email);
		if (!email.contains("@")) {
			return error(ERROR_CODE_EMAIL_IS_INVALID, "email is not valid to be registered");
		}
		boolean newUser = false; 
		if (pu == null) {
			newUser = false;
			boolean premiumPresent = checkOrderIdPremium(orderid);
			if (!premiumPresent) {
				return error(ERROR_CODE_NO_VALID_SUBSCRIPTION, "no valid subscription is present");
			}

			pu = new PremiumUsersRepository.PremiumUser();
			pu.email = email;
			pu.regTime = new Date();
		}
		
		pu.tokendevice = deviceId;
		pu.token = (new Random().nextInt(8999) + 1000) + "";
		pu.tokenTime = new Date();
		usersRepository.saveAndFlush(pu);
		emailSender.sendRegistrationEmail(pu.email, pu.token, newUser);
		return ok();
	}

    
	@PostMapping(value = "/device-register")
	@ResponseBody
	public ResponseEntity<String> deviceRegister(@RequestParam(name = "email", required = true) String email,
			@RequestParam(name = "token", required = true) String token,
			@RequestParam(name = "deviceid", required = true) String deviceId,
			@RequestParam(name = "orderid", required = false) String orderid)
			throws IOException {
		PremiumUser pu = usersRepository.findByEmail(email);
		if (pu == null) {
			return error(ERROR_CODE_USER_IS_NOT_REGISTERED, "user with that email is not registered");
		}
		if (pu.token == null || !pu.token.equals(token) || pu.tokenTime == null || System.currentTimeMillis()
				- pu.tokenTime.getTime() > TimeUnit.MILLISECONDS.convert(24, TimeUnit.HOURS)) {
			return error(ERROR_CODE_TOKEN_IS_NOT_VALID_OR_EXPIRED, "token is not valid or expired (24h)");
		}
		pu.token = null;
		pu.tokenTime = null;
		PremiumUserDevice device = new PremiumUserDevice();
		device.userid = pu.id;
		device.deviceid = deviceId;
		device.orderid = orderid;
		device.udpatetime = new Date();
		device.accesstoken = UUID.randomUUID().toString();
		usersRepository.saveAndFlush(pu);
		devicesRepository.saveAndFlush(device);
		return ResponseEntity.ok(gson.toJson(device));
	}
    
	
	@PostMapping(value = "/delete-file")
	@ResponseBody
	public ResponseEntity<String> delete(@RequestParam(name = "name", required = true) String name, @RequestParam(name = "type", required = true) String type,
			@RequestParam(name = "deviceid", required = true) int deviceId,
			@RequestParam(name = "accessToken", required = true) String accessToken)
			throws IOException {
		PremiumUserDevice dev = checkToken(deviceId, accessToken);
		if (dev == null) {
			return tokenNotValid();
		}
		UserFile usf = new PremiumUserFilesRepository.UserFile();
		usf.name = name;
		usf.type = type;
		usf.updatetime = new Date();
		usf.userid = dev.userid;
		usf.deviceid = deviceId;
		usf.data = null;
		usf.filesize = -1;
		filesRepository.saveAndFlush(usf);
		return ok();
	}
	
	@PostMapping(value = "/upload-file", consumes = MULTIPART_FORM_DATA_VALUE)
	@ResponseBody
	public ResponseEntity<String> upload(@RequestPart(name = "file") @Valid @NotNull @NotEmpty MultipartFile file,
			@RequestParam(name = "name", required = true) String name, @RequestParam(name = "type", required = true) String type,
			@RequestParam(name = "deviceid", required = true) int deviceId,
			@RequestParam(name = "accessToken", required = true) String accessToken)
			throws IOException {
		PremiumUserDevice dev = checkToken(deviceId, accessToken);
		if (dev == null) {
			return tokenNotValid();
		}
		UserFile usf = new PremiumUserFilesRepository.UserFile();
		int cnt, sum;
		try {
			GZIPInputStream gzis = new GZIPInputStream(file.getInputStream());
			byte[] buf = new byte[1024];
			sum = 0;
			while((cnt =gzis.read(buf)) >= 0 ) {
				sum += cnt;
			}
		} catch (IOException e) {
			return error(ERROR_CODE_GZIP_ONLY_SUPPORTED_UPLOAD, "File is submitted not in gzip format");
		}
		usf.name = name;
		usf.type = type;
		usf.updatetime = new Date();
		usf.userid = dev.userid;
		usf.deviceid = deviceId;
		usf.filesize = sum;
//		Session session = entityManager.unwrap(Session.class);
//	    Blob blob = session.getLobHelper().createBlob(file.getInputStream(), file.getSize());
//		usf.data = blob;
		 
		usf.data = file.getBytes();
		filesRepository.saveAndFlush(usf);
		return ok();
	}
	

	
	@GetMapping(value = "/download-file")
	@ResponseBody
	public void getFile(HttpServletResponse response, HttpServletRequest request,
			@RequestParam(name = "name", required = true) String name, @RequestParam(name = "type", required = true) String type,
			@RequestParam(name = "deviceid", required = true) int deviceId,
			@RequestParam(name = "accessToken", required = true) String accessToken) throws IOException, SQLException {
		ResponseEntity<String> error = null;
		UserFile fl = null;
		PremiumUserDevice dev = checkToken(deviceId, accessToken);
		if (dev == null) {
			error = tokenNotValid();
		} else {
			fl = filesRepository.findTopByUseridAndNameAndTypeOrderByUpdatetimeDesc(dev.userid, name, type);
			if (fl == null || fl.data == null) {
				error = error(ERROR_CODE_FILE_NOT_AVAILABLE, "File is not available");
			}
		}
		if (error != null) {
			response.setStatus(error.getStatusCodeValue());
			response.getWriter().write(error.getBody());
			return;
		}
		response.setHeader("Content-Disposition", "attachment; filename=" + fl.name);
		// InputStream bin = fl.data.getBinaryStream();
		InputStream bin = new ByteArrayInputStream(fl.data);
		String acceptEncoding = request.getHeader("Accept-Encoding");
		if (acceptEncoding != null && acceptEncoding.contains("gzip")) {
			response.setHeader("Content-Encoding", "gzip");
		} else {
			bin = new GZIPInputStream(bin);
		}
		response.setContentType(APPLICATION_OCTET_STREAM.getType());
		byte[] buf = new byte[1024];
		int r;
		while ((r = bin.read(buf)) != -1) {
			response.getOutputStream().write(buf, 0, r);
		}
	}


	private ResponseEntity<String> tokenNotValid() {
		return error(ERROR_CODE_PROVIDED_TOKEN_IS_NOT_VALID, "provided deviceid or token is not valid");
	}

	
	@GetMapping(value = "/list-files")
	@ResponseBody
	public ResponseEntity<String> listFiles( 
			@RequestParam(name = "deviceid", required = true) int deviceId, @RequestParam(name = "accessToken", required = true) String accessToken, 
			@RequestParam(name = "name", required = false) String name, @RequestParam(name = "type", required = false) String type,
			@RequestParam(name = "allVersions", required = false, defaultValue = "false") boolean allVersions) throws IOException, SQLException {
		PremiumUserDevice dev = checkToken(deviceId, accessToken);
		if (dev == null) {
			return tokenNotValid();
		}
		List<UserFileNoData> fl = filesRepository.listFilesByUserid(dev.userid, name, type);
		UserFilesResults res = new UserFilesResults();
		res.uniqueFiles = new ArrayList<>();
		if (allVersions) {
			res.allFiles = new ArrayList<>();
		}
		res.deviceid = deviceId;
		Set<String> fileIds = new TreeSet<String>();
		for (UserFileNoData sf : fl) {
			String fileId = sf.type + "____" + sf.name;
			if (fileIds.add(fileId)) {
				if (sf.filesize >= 0) {
					res.uniqueFiles.add(sf);
				}
			}
			if (allVersions) {
				res.allFiles.add(sf);
			}
		}
		return ResponseEntity.ok(gson.toJson(res));
	}
	
	public static class UserFilesResults {
		public List<UserFileNoData> allFiles;
		public List<UserFileNoData> uniqueFiles;
		public int deviceid;
		
	}
}