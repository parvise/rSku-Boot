package com.hp.c4.rsku.rSku.rest.services;

import java.io.File;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hp.c4.rsku.rSku.bean.request.C4BaseProduct;
import com.hp.c4.rsku.rSku.bean.request.RSkuProduct;
import com.hp.c4.rsku.rSku.bean.request.RSkuRequest;
import com.hp.c4.rsku.rSku.bean.request.TCosData;
import com.hp.c4.rsku.rSku.bean.request.TPeriodData;
import com.hp.c4.rsku.rSku.bean.response.PLMaskElements;
import com.hp.c4.rsku.rSku.bean.response.ProductCost;
import com.hp.c4.rsku.rSku.bean.response.RSkuResponse;
import com.hp.c4.rsku.rSku.c4.util.Aperiod;
import com.hp.c4.rsku.rSku.c4.util.C4Exception;
import com.hp.c4.rsku.rSku.constants.C4RskuLabelConstants;
import com.hp.c4.rsku.rSku.constants.DBConstants;
import com.hp.c4.rsku.rSku.dbio.persistent.cache.Cache;
import com.hp.c4.rsku.rSku.dbio.persistent.cache.PlMappingData;
import com.hp.c4.rsku.rSku.dbio.persistent.mapping.io.CSubmitCosIO;
import com.hp.c4.rsku.rSku.pojo.DefaultMotTradingExpense;
import com.hp.c4.rsku.rSku.pojo.DefaultPriceDescriptor;
import com.hp.c4.rsku.rSku.pojo.MaskElementTypes;
import com.hp.c4.rsku.rSku.pojo.Product;
import com.hp.c4.rsku.rSku.security.server.util.C4SecurityException;
import com.hp.c4.rsku.rSku.security.server.util.SFTPUtil;
import com.hp.c4.rsku.rSku.security.server.util.UtilConstants;
import com.hp.c4.rsku.rSku.security.server.util.ValidateC4RskuRequest;
import com.hp.c4.rsku.rSku.security.server.util.constatnts.DeliveryMethodConstants;
import com.hp.c4.rsku.rSku.security.server.util.icost.C4LoaderBindingImpl;
import com.hp.c4.rsku.rSku.security.server.util.icost.C4LoaderPortType;
import com.hp.c4.rsku.rSku.security.server.util.icost.CcosCost;
import com.hp.c4.rsku.rSku.security.server.util.icost.Cheader;

@Service
public class C4CostProcessingService extends C4RskuLabelConstants {

	private static final Logger mLogger = LogManager.getLogger(C4CostProcessingService.class);

	@Autowired
	private PLMaskElementsService pLMaskElementsService;

	@Autowired
	private C4AggregateCostCalculationService c4AggregateCostCalculationService;

	protected static final String WorldWide = "Worldwide";
	private static final String notFound = "NOT_FOUND";

	// The return values from getPermission for either PL or MENU
	public static final int OPERATION_PERMITTED = 1;
	public static final int PERMISSION_DENIED = -2;
	public static final int OPERATION_NOT_PERMITTED = 0;

	private Map<Product, String> accessDeinedInfo = new LinkedHashMap<Product, String>();

	private final String NOT_IN_HIERARCHY = "product not loaded in hierarchy";
	private final String BELONGS_TO_PL = "product belongs to PL ";

	// Cost elements used by the formulas
	public final static String OUTPUTFOREX = "OUTPUTFOREX";
	public final static String MATRL = "MATRL";
	public final static String VWRTY = "VWRTY";
	public final static String VTRDX = "VTRDX";
	public final static String VRLTY = "VRLTY";
	public final static String OVBLC = "OVBLC";
	public final static String MALAD = "MALAD";
	public final static String OFXDC = "OFXDC";
	public final static String DEFAULT_DELIVERY = VTRDX;
	public final static String EXWTX = "EXWTX";
	public final static String FCATX = "FCATX";
	public final static String FOBTX = "FOBTX";
	public final static String CPTTX = "CPTTX";
	public final static String CIPTX = "CIPTX";
	public final static String DAFTX = "DAFTX";
	public final static String DDUTX = "DDUTX";

	// Additional MOTS
	public final static String DDUTR = "DDUTR";
	public final static String VTRTR = "VTRTR";

	// MPO R2: MOT : new trading expenses
	public final static String DDURA = "DDURA";
	public final static String DDUSE = "DDUSE";
	public final static String DDUEX = "DDUEX";
	public final static String VTRRA = "VTRRA";
	public final static String VTRSE = "VTRSE";
	public final static String VTREX = "VTREX";

	private final static String DELIVERY = "DELIVERY";

	private final static String COST_STATUS_COMPLETE = "COMPLETE";
	private final static String COST_STATUS_IN_COMPLETE = "IN-COMPLETE";
	private final static String COST_STATUS_WARNING = "WARNING";
	private final static String COST_STATUS_NOT_FOUND = "NOT-FOUND";

	private RSkuResponse response = new RSkuResponse();

	@Autowired
	private ValidateC4RskuRequest validateC4RskuRequest;

	@Autowired
	private MOTTradingExpenseService mOTTradingExpenseService;

	@Value("${c4.rsku.importer.files.from.location}")
	private String fromLocation;

	@Value("${c4.rsku.importer.files.to.location}")
	private String toLocation;

	@Value("${rsku.transfer.file.authenticate.local.user.allow}")
	private String isDbConfig;

	@Value("${rsku.transfer.file.authenticate.host}")
	private String stageServerHostName;

	@Value("${rsku.transfer.file.authenticate.local.userName}")
	private String stageServerUserName;

	@Value("${rsku.transfer.file.authenticate.local.password}")
	private String stageServerPwd;

	@Value("${rsku.staging.importer.sender.not.mailids}")
	private String notificationMailIds;

	private RSkuRequest request;

	private DecimalFormat decimalFormat = new DecimalFormat("##.00");

	private Map<String, List<MaskElementTypes>> defaultCostElementTypes;

	public Object validateRskuRequest(RSkuRequest request) {
		this.request = request;

		mLogger.info("Performing C4 RSKU request user input Validations.......");

		Map<String, String> errorCodes = new LinkedHashMap<String, String>();
		try {
			errorCodes = validateC4RskuRequest.validateRskuRequestFields(request);

			if (errorCodes.size() > 0) {
				mLogger.info("Validation fails refer the response......... " + errorCodes);
				return errorCodes;
			}
		} catch (C4SecurityException e1) {
			errorCodes.put(C4_EXCEPTION_KEY, C4_EXCEPTION_VALUE);
			mLogger.error("Validation fails refer the response......... " + errorCodes);
			return errorCodes;
		}
		DefaultPriceDescriptor pdesc = null;

		try {

			String[] dateList = new String[] { request.getCostDate() };
			String[] c4CostDates = request.getC4CostDates();

			mLogger.info("Pass the C4 Cost dates validation....Completes" + Arrays.toString(c4CostDates));

			pdesc = request.getDefaultPriceDescriptor();

			mLogger.info("Pass the Price Descriptor validation....Completes" + pdesc);

			Map<Character, TPeriodData> periodIdMap = Cache.findTPeriodIds(c4CostDates);
			mLogger.info("C4 PEriod ID's are available" + periodIdMap);

			com.hp.c4.rsku.rSku.pojo.Product[] prodList = getListOfUniqueProducts(request.getListOfProducts());

			String[] pls = getPLMappingData(prodList);

			Product[] accessedProducts = checkAccess(prodList, pls, pdesc);

			response.setAccessDeniedProducts(accessDeinedInfo);

			response.setCountryCode(pdesc.getGeoCode());
			response.setCountryDesc(pdesc.getCountry());

			mLogger.info(DeliveryMethodConstants.valueOf(request.getDeliveryMethod()).getDeliveryCodeDesc());
			response.setDeliveryMethod(
					DeliveryMethodConstants.valueOf(request.getDeliveryMethod()).getDeliveryCodeDesc());
			response.setMot(request.getMot().toUpperCase());
			response.setOutputCurrency(request.getOutputCurrency());

			Date[] _dateList = Aperiod.getAscendingDates(dateList);
			extratctCostElements(accessedProducts, c4AggregateCostCalculationService.getC4CostOfSkus(pdesc,
					accessedProducts, dateList, periodIdMap, _dateList));

			Calendar c = Calendar.getInstance();
			SimpleDateFormat sdf1 = new SimpleDateFormat("yyyy/MM/dd");
			SimpleDateFormat sdf = new SimpleDateFormat("dd-MMM-yy");
			mLogger.info("Cost dates Weekly,Monthly,Qarterly......" + Arrays.toString(c4CostDates));
			c.setTime(sdf.parse(c4CostDates[2]));
			String quarterDate = sdf1.format(c.getTime());
			mLogger.info("Qarter Date:......." + quarterDate);
			response.setCostDate(quarterDate);

		} catch (C4SecurityException e) {
			mLogger.error("Exception occuret at ......." + e.getMessage());
			errorCodes.put(C4_EXCEPTION_KEY, C4_EXCEPTION_VALUE);
			mLogger.error("Validation fails refer the response......... " + errorCodes);
			return errorCodes;
		} catch (ParseException e) {
			mLogger.error("Exception occuret at ......." + e.getMessage());
			errorCodes.put(C4_EXCEPTION_KEY, C4_EXCEPTION_VALUE);
			mLogger.error("Validation fails refer the response......... " + errorCodes);
			return errorCodes;
		}

		ProductCost rSku = response.getRapidSkuCostDetails();

		Map<String, Float> costElements = rSku.getSkuCostElements();
		if (costElements != null && costElements.size() > 0) {
			generateImporterProcessFiles();
			mLogger.info("C4 RSKU Request service........ Completes and Now Cost is available in Response ");
		} else {
			mLogger.info("C4 RSKU Request service........ Completes... Cost is Not found.... ");
		}

		mLogger.info(
				"################################ Service Ends #####################################################");
		return response;

	}

	private String getPLString(String[] arrPL) {
		StringBuffer sbuff = new StringBuffer();
		try {
			if (arrPL != null) {

				for (int i = 0; i < arrPL.length; i++) {
					if (arrPL[i].equalsIgnoreCase("NULL"))// dont add NULL PL
						continue;
					sbuff.append(arrPL[i]);
					sbuff.append(",");
				}
				if (sbuff.length() > 0 && sbuff.charAt(sbuff.length() - 1) == ',')
					sbuff.deleteCharAt(sbuff.length() - 1);
			}

		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return sbuff.toString();
	}

	private String getStringFromArray(String[] str, String seperator) {
		StringBuffer sbuff = new StringBuffer();
		if (str != null) {
			for (int i = 0; i < str.length; i++) {
				sbuff.append(str[i]);
				if ((i + 1) != str.length)
					sbuff.append(seperator);
			}
		}
		return sbuff.toString();
	}

	private void generateImporterProcessFiles() {

		mLogger.info("Generating the Cost files at Staging location started.....");
		C4LoaderPortType port = new C4LoaderBindingImpl();
		Cheader header = new Cheader();
		header.setAuthor(IMPORTER_NOTIFY_MAIL);
		header.setSender(IMPORTER_NOTIFY_MAIL);

		String[] authorizedRegions = Cache.getUpdateGeosForUser();
		String[] userProductLines = Cache.getUpdatePLsForUser();

		header.setPls(getPLString(userProductLines));
		header.setRegions(getStringFromArray(authorizedRegions, ","));
		mLogger.info("Author notificationMailIds :" + notificationMailIds);
		header.setEmail(notificationMailIds);

		header.setBatchType("Full");
		header.setPeriodType("Quarter");
		String c4Date;
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy/MM/dd");

		c4Date = simpleDateFormat.format(new Date(response.getCostDate()));

		header.setStartDate(c4Date); // 8/1/2021 12:00:00 AM

		// header.setCreationDate("2002/12/31"); // 9/15/2021
		// header.setEffectiveDate("2003/02/01");// 9/14/2021 12:25:01 PM

		simpleDateFormat = new SimpleDateFormat("yyyy/MM/dd");
		simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		String creationDate;

		try {
			creationDate = simpleDateFormat.format(new Date());
			header.setCreationDate(creationDate);
			// at this point now effective date contains the date in importer timezone
		} catch (Exception ex) {
			mLogger.info("Exception occured at generateImporterProcessFiles" + ex.getMessage());
		}

		simpleDateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
		simpleDateFormat.setTimeZone(TimeZone.getTimeZone("CST"));
		String effectiveDate;
		try {
			effectiveDate = simpleDateFormat.format(new Date());
			header.setEffectiveDate(effectiveDate);
			// at this point now effective date contains the date in importer timezone
		} catch (Exception ex) {
			mLogger.info("Exception occured at generateImporterProcessFiles" + ex.getMessage());
		}

		simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
		simpleDateFormat.setTimeZone(TimeZone.getTimeZone("CST"));
		// C_F_20211006_

		Random random = new Random();

		// int randomNum = random.ints(1,
		// userProductLines.length).findFirst().getAsInt();

		int randomNum = (int) (Math.random() * (userProductLines.length - 1)) + 1;
		mLogger.info("Pickup PL......:::" + userProductLines[randomNum - 1]);

		// build filename prefix & segment
		String sFileNamePrefix = "C_" + ("Full".equals("Full") ? "F" : "P") + "_" + simpleDateFormat.format(new Date())
				+ "_01_" + userProductLines[randomNum - 1];

		String fromFolder = fromLocation;
		String fileName = fromFolder + sFileNamePrefix;

		try {
			port.setHeader(fileName, header);
		} catch (RemoteException e) {
			mLogger.info("Exception occured at generateImporterProcessFiles" + e.getMessage());
		}

		ProductCost rSku = response.getRapidSkuCostDetails();

		Map<String, Float> costElements = rSku.getSkuCostElements();
		simpleDateFormat = new SimpleDateFormat("yyyy/MM/dd");
		List<CcosCost> list = new ArrayList<CcosCost>();
		for (String element : costElements.keySet()) {
			CcosCost data = new CcosCost();
			data.setProductId(rSku.getProdIdBase());
			data.setOpt("");
			data.setSpn("");
			data.setMcc("");
			data.setCountry("");
			data.setRegion("");
			data.setWw("WW");
			data.setElementType(element);
			data.setCost(costElements.get(element));
			data.setPeriodStart(c4Date);
			list.add(data);
		}

		try {
			port.setCosFullData(fileName, list.toArray(new CcosCost[list.size()]), true);
		} catch (RemoteException e) {
			mLogger.info("Exception occured at generateImporterProcessFiles" + e.getMessage());
		}

		insertRSkuProductDetailsIntoDb();

		transferFiles(sFileNamePrefix, fromFolder);

	}

	private void insertRSkuProductDetailsIntoDb() {
		mLogger.info("Insert/Updating the RSKU product details into C4 DB started......");
		ProductCost rSKUDetails = response.getRapidSkuCostDetails();
		String productId = rSKUDetails.getProdIdBase();
		String prodDesc = request.getRapidSku().getProduct().getrSkuDec();
		String pl = rSKUDetails.getMaskElements().getPl();
		Cache.storeRapidSkuDetails(productId, pl, prodDesc);
		mLogger.info("Insert/Updating the RSKU product details into C4 DB Done......");
	}

	private void transferFiles(String fileName, String fromFolder) {

		mLogger.info("File Transfer though SFT is started........" + isDbConfig);
		String stageServer = null;
		String username = null;
		String password = null;
		if (isDbConfig.equalsIgnoreCase(Boolean.TRUE.toString())) {

			stageServer = stageServerHostName; // "impitg.inc.hpicorp.net";
			username = stageServerUserName;
			password = stageServerPwd;

		} else {
			CSubmitCosIO io = new CSubmitCosIO(DBConstants.C4_DBPOOL_C4PROD_OFFI);
			try {
				@SuppressWarnings("unchecked")
				HashMap<String, String> map = (HashMap<String, String>) io
						.getHostUserPwd(UtilConstants.USED_FOR_STAGING);

				stageServer = map.get(UtilConstants.HOST).toString();
				username = map.get(UtilConstants.USERNAME).toString();
				password = map.get(UtilConstants.PASSWORD).toString();
			} catch (C4Exception e) {
				mLogger.error("Error occured File Transfer though SFT ........" + e.getMessage());
			}
		}
		String copyWinFrom = fromFolder + fileName + ".xml";
		String copyWinTo = toLocation + fileName + ".xml";
		mLogger.info("Copy from " + copyWinFrom);
		mLogger.info("Copy To " + copyWinTo);
		File file = null;
		try {
			SFTPUtil sftpUtil = new SFTPUtil(username, password, stageServer);
			try {
				file = new File(copyWinFrom);
				sftpUtil.connect();
				// sftpUtil.changeDirectory(copyWinTo);
				sftpUtil.putFile(copyWinFrom, copyWinTo);
				sftpUtil.disConnect();

			} catch (Exception excp) {
				mLogger.fatal("C4FTPStager.putFile - Exception: " + excp);
			}

			mLogger.info("XML file staging done....");
			file = new File(copyWinFrom);
			boolean ok = file.delete();
			if (ok == true)
				mLogger.info("Deleted temp file " + copyWinFrom);

			copyWinFrom = fromFolder + fileName + ".dat";
			copyWinTo = toLocation + fileName + ".dat";
			sftpUtil = new SFTPUtil(username, password, stageServer);
			try {
				file = new File(copyWinFrom);
				sftpUtil.connect();
				// sftpUtil.changeDirectory(copyWinTo);
				sftpUtil.putFile(copyWinFrom, copyWinTo);
				sftpUtil.disConnect();

			} catch (Exception excp) {
				mLogger.fatal("C4FTPStager.putFile - Exception: " + excp);
			}

			mLogger.info("DAT file staging done....");
			file = new File(copyWinFrom);
			ok = file.delete();
			if (ok == true)
				mLogger.info("Deleted temp file " + copyWinFrom);
		} catch (Exception e1) {
			mLogger.error("Error occured at File Transfer though SFT ........" + e1.getMessage());
		}
		mLogger.info("File Transfer though SFT done........");
	}

	/**
	 * Extracting the Cost and performing Rollup the Cost to Rsku
	 * 
	 * @param accessedProducts
	 * @param productCostMap
	 */
	private void extratctCostElements(Product[] accessedProducts,
			Map<com.hp.c4.rsku.rSku.pojo.Product, Set<TCosData>> productCostMap) {
		Map<Product, Map<String, Float>> allSkuCostElementsMap = new LinkedHashMap<Product, Map<String, Float>>();
		mLogger.info("Individual Cost elemnts roll-up is started...... ");

		Map<String, Float> sumUpAllSkuElementsCost = new LinkedHashMap<String, Float>();

		for (Product product : accessedProducts) {
			Map<String, Float> elemntsCost = allSkuCostElementsMap.get(product);
			if (elemntsCost == null) {
				elemntsCost = new HashMap<String, Float>();
			}
			Set<TCosData> tcosDataList = productCostMap.get(product);

			for (TCosData tCosData : tcosDataList) {
				if (tCosData.getProdId().equalsIgnoreCase(product.getProdId())) {
					elemntsCost.put(tCosData.getElementType(), tCosData.getCost());
					Float elementCost = tCosData.getCost();
					if (sumUpAllSkuElementsCost.get(tCosData.getElementType()) != null)
						elementCost += sumUpAllSkuElementsCost.get(tCosData.getElementType());
					sumUpAllSkuElementsCost.put(tCosData.getElementType(),
							Float.parseFloat(decimalFormat.format(elementCost)));
				}
			}

			allSkuCostElementsMap.put(product, elemntsCost);
		}

		mLogger.info("Individual Cost elemnts roll-up is Done...... ");
		mLogger.info(allSkuCostElementsMap);
		mLogger.info(sumUpAllSkuElementsCost);
		calculateTCost(accessedProducts, allSkuCostElementsMap, sumUpAllSkuElementsCost);

	}

	private void calculateTCost(Product[] accessedProducts, Map<Product, Map<String, Float>> allSkuCostElementsMap,
			Map<String, Float> sumUpAllSkuElementsCost) {

		mLogger.info("Calculating the C4 Total Cost started processing.......");
		try {

			Map<String, List<MaskElementTypes>> allMaskElements = pLMaskElementsService.getAllPLMasks();

			defaultCostElementTypes = pLMaskElementsService.getAllDefaultMasElementTypes();

			PlMappingData[] plData = Cache.getPlMappingInfo(accessedProducts);
			int count = 0;
			ProductCost[] baseSkuCostDetails = new ProductCost[accessedProducts.length];
			// Calculating Tcos formula for base SKU's
			for (PlMappingData plMappingData : plData) {
				baseSkuCostDetails[count] = applyingC4TotalCostFormula(allSkuCostElementsMap, request, allMaskElements,
						plMappingData);
				count++;
			}
			response.setBaseSkuCostDetails(baseSkuCostDetails);

			RSkuProduct rskProduct = request.getRapidSku().getProduct();
			Product[] rSKU = getListOfUniqueProducts(new RSkuProduct[] { rskProduct });

			Map<Product, Map<String, Float>> sumUpAllSkuElementsCostMap = new LinkedHashMap<Product, Map<String, Float>>();
			sumUpAllSkuElementsCostMap.put(rSKU[0], sumUpAllSkuElementsCost);
			PlMappingData plMapping = new PlMappingData(rSKU[0], request.getRapidSku().getPl(), null);

			if (sumUpAllSkuElementsCostMap.size() > 0) {
				Map<String, Float> rapidSkuCostElements = sumUpAllSkuElementsCostMap.get(plMapping.get_prod());
				if (rapidSkuCostElements.containsKey(VTRDX)) {
					Float vtrdx = rapidSkuCostElements.get(VTRDX);
					rapidSkuCostElements.put(DDUTX, vtrdx);
					rapidSkuCostElements.put(DDUTR, vtrdx);
					rapidSkuCostElements.put(VTRTR, vtrdx);
				}

				if (rapidSkuCostElements.containsKey(VTRSE)) {
					Float vtrse = rapidSkuCostElements.get(VTRSE);
					rapidSkuCostElements.put(VTRRA, vtrse);
					rapidSkuCostElements.put(DDUSE, vtrse);
					rapidSkuCostElements.put(DDURA, vtrse);
				}
			}

			// Calculating Tcos formula for Rapid SKU's
			ProductCost rapidSkuCostDetails = applyingC4TotalCostFormula(sumUpAllSkuElementsCostMap, request,
					allMaskElements, plMapping);

			response.setRapidSkuCostDetails(rapidSkuCostDetails);

		} catch (SQLException e) {
			mLogger.error("Exception occured at calculateTCost method....." + e.getMessage());
		}
	}

	private ProductCost applyingC4TotalCostFormula(Map<Product, Map<String, Float>> allSkuCostElementsMap,
			RSkuRequest request, Map<String, List<MaskElementTypes>> allMaskElements, PlMappingData plMappingData)
			throws SQLException {
		Map<String, DefaultMotTradingExpense> defaultMotMap = mOTTradingExpenseService.getDefaultMotExpense();
		String deliveryMethod = request.getDeliveryMethod() + "#" + request.getMot();
		DefaultMotTradingExpense defaultMot = defaultMotMap.get(deliveryMethod.toUpperCase());

		final String[] mandatoryElements = { MALAD, MATRL, VWRTY, VRLTY, OVBLC, OFXDC };
		List<String> mandatoryList = Arrays.asList(mandatoryElements);

		List<MaskElementTypes> elementsList = null;

		if (plMappingData.get_platform() == null) {
			elementsList = allMaskElements.get(plMappingData.get_pl());
		} else {
			String theKey = getMaskKey(plMappingData.get_pl(), plMappingData.get_platform());
			elementsList = allMaskElements.get(theKey);
			if (elementsList == null && allMaskElements.containsKey(plMappingData.get_pl())) {
				elementsList = allMaskElements.get(plMappingData.get_pl());
			}
			if (elementsList == null) {
				elementsList = new ArrayList<MaskElementTypes>();
				Collection<List<MaskElementTypes>> list = defaultCostElementTypes.values();
				mLogger.info("This PL doesn't exist Mask Elements capturing default..." + plMappingData);
				for (List<MaskElementTypes> maskElementTypes : list) {
					elementsList.addAll(maskElementTypes);
				}
			}
		}
		ProductCost productCost = null;
		if (elementsList != null) {

			Map<String, Float> singleSKuElementsCost = allSkuCostElementsMap.get(plMappingData.get_prod());
			Map<String, Float> elementsMap = new LinkedHashMap<String, Float>();
			String costStatus = "";
			boolean completeStatus = false, inCompleteStatus = false, warningStatus = false;
			List<MaskElementTypes> requiredElementsList = new ArrayList<MaskElementTypes>();
			for (MaskElementTypes maskElementTypes : elementsList) {

				if (mandatoryList.contains(maskElementTypes.getElementType())) {
					Float elementCost = 0.0f;
					if (maskElementTypes.getFlag() == 'R') {
						if (singleSKuElementsCost.containsKey(maskElementTypes.getElementType())) {
							requiredElementsList.add(maskElementTypes);
							completeStatus = true;
						} else {

							inCompleteStatus = true;
						}
					}
					if (singleSKuElementsCost.get(maskElementTypes.getElementType()) != null)
						elementCost = singleSKuElementsCost.get(maskElementTypes.getElementType());
					elementsMap.put(maskElementTypes.getElementType(), elementCost);
				} else if ((defaultMot.getDefaultElementType().equalsIgnoreCase(maskElementTypes.getElementType())
						|| singleSKuElementsCost.containsKey(defaultMot.getDefaultElementType()))
						&& !elementsMap.containsKey(DELIVERY)) {
					Float deliveryCost = singleSKuElementsCost.get(defaultMot.getElementType());
					if (deliveryCost == null) {

						warningStatus = true;
						deliveryCost = singleSKuElementsCost.get(defaultMot.getDefaultElementType());
					}
					if (deliveryCost == null)
						deliveryCost = new Float(0.0);
					elementsMap.put(DELIVERY, deliveryCost);
				}
			}
			if (warningStatus) {
				costStatus = COST_STATUS_WARNING;
			} else if (inCompleteStatus) {
				costStatus = COST_STATUS_IN_COMPLETE;
			} else if (completeStatus) {
				costStatus = COST_STATUS_COMPLETE;
			} else {
				costStatus = COST_STATUS_COMPLETE;
			}
//			mLogger.info("elementsMap" + elementsMap);
			if (request.getOutputCurrency().equalsIgnoreCase("USD")) {
				elementsMap.put(OUTPUTFOREX, 1.0f);
			}
			PLMaskElements maskElements = new PLMaskElements(plMappingData.get_pl(), plMappingData.get_platform(),
					elementsList);

			Float tCOS = applyTcosFormula(elementsMap);

			if (singleSKuElementsCost.size() == 0 && tCOS == 0.0) {
				costStatus = COST_STATUS_NOT_FOUND;
			}

			productCost = new ProductCost(plMappingData.get_prod().getProdId().toUpperCase(), singleSKuElementsCost,
					costStatus, tCOS, maskElements);

			// mLogger.info("plMappingData" + plMappingData);
			// mLogger.info("elementsList" + elementsList);

		}

		mLogger.info("C4 Product Cost is Avaliable for the requested SKU's .......");
		return productCost;

	}

	/**
	 * Gets all the required fields for the formula and calculate TCOS
	 */
	private float applyTcosFormula(Map<String, Float> elementsMap) {
		double matrl = Double.parseDouble("" + elementsMap.get(MATRL));
		double vwrty = Double.parseDouble("" + elementsMap.get(VWRTY));
		// double deliveryTX =
		// Double.parseDouble(""+((Element)elementsMap.get(delivery)).value);
		if (elementsMap.get(DELIVERY) == null) {
			elementsMap.put(DELIVERY, 0.0f);
		}
		double deliveryTX = Double.parseDouble("" + elementsMap.get(DELIVERY));
		double vrlty = Double.parseDouble("" + elementsMap.get(VRLTY));
		double ovblc = Double.parseDouble("" + elementsMap.get(OVBLC));
		double malad = Double.parseDouble("" + elementsMap.get(MALAD)) / 100;
		double ofxdc = Double.parseDouble("" + elementsMap.get(OFXDC));
		double outputforex = Double.parseDouble("" + elementsMap.get(OUTPUTFOREX));
		float tcos = Float
				.parseFloat("" + ((matrl * (1 + malad) + vwrty + deliveryTX + vrlty + ovblc + ofxdc) * outputforex));
		// mLogger.info("Tcos Calculation=" + elementsMap + ":" + tcos);
		return tcos;
	}

	public static String getMaskKey(String pPl, String pPlatform) {
		StringBuffer buffer = new StringBuffer();

		buffer.append(pPl);
		if (pPlatform != null && !pPlatform.equalsIgnoreCase("<null>")) {
			buffer.append("|");
			buffer.append(pPlatform);
		}

		return buffer.toString();
	}

	/*
	 * Remove Duplicate SKU's & convert the Request List of SKU's to C4 SKU's which
	 * contains MCC & SPN
	 */
	private com.hp.c4.rsku.rSku.pojo.Product[] getListOfUniqueProducts(Object[] listOfProducts) {
		com.hp.c4.rsku.rSku.pojo.Product[] prodList = new com.hp.c4.rsku.rSku.pojo.Product[listOfProducts.length];
		mLogger.info("Actual SKU's received from Rsku Request:" + listOfProducts.length);
		Set<Product> uniqueSkusFromRskuRequest = new LinkedHashSet<Product>();
		if (listOfProducts.length > 0) {

			int i = 0;
			if (listOfProducts[i] instanceof C4BaseProduct) {
				C4BaseProduct[] listBaseProducts = (C4BaseProduct[]) listOfProducts;
				for (C4BaseProduct product : listBaseProducts) {
					prodList[i] = new com.hp.c4.rsku.rSku.pojo.Product();
					prodList[i].setProdId(product.getProdIdBase().trim().toUpperCase());
					prodList[i].setOpt(null);
					prodList[i].setMcc(null);
					prodList[i].setSpn(null);
					uniqueSkusFromRskuRequest.add(prodList[i]);
					i++;
				}
			} else if (listOfProducts[i] instanceof RSkuProduct) {
				RSkuProduct[] liRSkuProducts = (RSkuProduct[]) listOfProducts;
				for (RSkuProduct product : liRSkuProducts) {
					prodList[i] = new com.hp.c4.rsku.rSku.pojo.Product();
					prodList[i].setProdId(product.getrSkuProd().trim().toUpperCase());
					prodList[i].setOpt(null);
					prodList[i].setMcc(null);
					prodList[i].setSpn(null);
					prodList[i].setRSku(true);
					prodList[i].setProdDesc(product.getrSkuDec());
					uniqueSkusFromRskuRequest.add(prodList[i]);
					i++;
				}
			}
		}

		int size = uniqueSkusFromRskuRequest.size();
		com.hp.c4.rsku.rSku.pojo.Product[] uniqueProducts = new com.hp.c4.rsku.rSku.pojo.Product[size];
		System.arraycopy(uniqueSkusFromRskuRequest.toArray(), 0, uniqueProducts, 0, size);
		mLogger.info("Uniques SKU's from RSKU request:" + size);
		return uniqueProducts;
	}

	private void reconcile(short[] in, boolean[] out) {
		for (int i = 0; i < out.length; i++) {
			int count = 0;
			for (int j = i * 3; count < 3; count++, j++) {
				boolean found = false;
				switch (in[j]) {
				case OPERATION_PERMITTED:
					out[i] = true;
					out[i] = true;
					found = true;
					break;

				case PERMISSION_DENIED:
					out[i] = false;
					out[i] = false;
					found = true;
					break;

				case OPERATION_NOT_PERMITTED:
					out[i] = false;
					out[i] = false;
					break;

				default:
				}
				if (found)
					break;
			}
		}
	}

	private String[] getPLMappingData(com.hp.c4.rsku.rSku.pojo.Product[] prodList) {
		mLogger.info("PL Mapping for the Requested Base SKU's");
		try {
			String[] thePLs = new String[prodList.length];
			PlMappingData[] data = Cache.getPlMappingInfo(prodList);

			for (int i = 0; i < data.length; i++) { // we populate the result arrya with the values
				thePLs[i] = data[i].get_pl();
			}
			return thePLs;
		} catch (SQLException e) {
			mLogger.error("Exception occured at ...." + e.getMessage());
		}
		return null;
	}

	/**
	 * Filtering all the Requested SKU's are belongs Valid PL's or Not.
	 * 
	 * @param prodList
	 * @param pls
	 * @param pdesc
	 * @return
	 * @throws C4SecurityException
	 */
	private Product[] checkAccess(com.hp.c4.rsku.rSku.pojo.Product[] prodList, String[] pls,
			DefaultPriceDescriptor pdesc) throws C4SecurityException {
		mLogger.info("Checking for SKU's are Valid PL's which belongs to HPI/Not........");
		StringBuffer sbf = null;
		int count = 0;
		for (int i = 0; i < prodList.length; i++) {
			if (!notFound.equals(pls[i]))
				count++;
		}
		boolean[] p = null;
		String[] res = new String[count];
		short[] ret = new short[count * 3];
		p = new boolean[count];
		int[] missedOut = new int[prodList.length - count];
		int missedOutCounter = 0;
		int counter = 0;

		for (int i = 0; i < prodList.length; i++) {
			if (!notFound.equals(pls[i])) {
				sbf = new StringBuffer();
				sbf.append(pls[i]);
				sbf.append("~");
				sbf.append(WorldWide);
				sbf.append("~");
				sbf.append(pdesc.getRegion());
				sbf.append("~");
				sbf.append(pdesc.getCountry());
				res[counter++] = sbf.toString();
//	          res[counter++] = pls[i]+"~"+WorldWide + "~" + geoDesc.region + "~" + geoDesc.country;
			} else {
				missedOut[missedOutCounter++] = i;
			}
		}
		// mLogger.info("Checking The List of PL's" + "::::" + Arrays.toString(res) +
		// "For PriceDesc ::" + pdesc);

		mLogger.info("Getting the Permissions for the Pls statred......");
		ret = Cache.getPermissions(res);
		boolean[] returnResult = null;
		reconcile(ret, p);
		returnResult = new boolean[prodList.length];
		int incretr = 0;
		for (int i = 0; i < prodList.length; i++) {
			boolean missOut = false;
			for (int j = 0; j < missedOutCounter; j++) {
				if (missedOut[j] == i) {
					returnResult[i] = true;
					missOut = true;
					break;
				}
			}
			if (!missOut) {
				returnResult[i] = p[incretr++];
			}
		}

		ArrayList<Integer> deniedIndexes = new ArrayList<Integer>();
		ArrayList<Product> deniedProds = new ArrayList<Product>();
		ArrayList<Product> acceptedProds = new ArrayList<Product>();
		if (accessDeinedInfo != null) {
			accessDeinedInfo.clear();
		}
		for (int i = 0; i < returnResult.length; i++) {
			if (returnResult[i])
				acceptedProds.add(prodList[i]);
			else {
				deniedIndexes.add(new Integer(i));
				deniedProds.add(prodList[i]);

				String PL = pls[i];
				if (PL != null) {
					PL = BELONGS_TO_PL + PL;
				} else {
					PL = NOT_IN_HIERARCHY;
				}
				accessDeinedInfo.put(prodList[i], PL);
			}

		}

		if (deniedProds.size() != 0) {
			int[] indexes = new int[deniedProds.size()];
			for (int i = 0; i < deniedProds.size(); i++) {
				indexes[i] = ((Integer) deniedIndexes.get(i)).intValue();
			}
		}
		mLogger.info("User requested Access Denied Info : " + ":" + accessDeinedInfo);
		mLogger.info("User requested Access Products Info : " + ":" + acceptedProds);
		mLogger.info("Getting the Permissions for the Pls Done......");
		return (Product[]) acceptedProds.toArray(new Product[acceptedProds.size()]);
	}

}
