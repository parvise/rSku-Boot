package com.hp.c4.rsku.rSku.dbio.persistent.cache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.hp.c4.rsku.rSku.bean.request.TPeriodData;
import com.hp.c4.rsku.rSku.c4.util.C4Exception;
import com.hp.c4.rsku.rSku.constants.DBConstants;
import com.hp.c4.rsku.rSku.dbio.persistent.CdbConnectionMgr;
import com.hp.c4.rsku.rSku.dbio.persistent.mapping.io.CpricetermMappingIO;
import com.hp.c4.rsku.rSku.pojo.DefaultMotTradingExpense;
import com.hp.c4.rsku.rSku.pojo.DefaultPriceDescriptor;
import com.hp.c4.rsku.rSku.pojo.MaskElementTypes;
import com.hp.c4.rsku.rSku.security.server.business.LoginDelegate;
import com.hp.c4.rsku.rSku.security.server.business.PermissionDelegate;
import com.hp.c4.rsku.rSku.security.server.business.UserDelegate;
import com.hp.c4.rsku.rSku.security.server.util.C4SecurityException;
import com.hp.c4.rsku.rSku.security.server.util.SQLUtil;

import oracle.sql.ARRAY;
import oracle.sql.ArrayDescriptor;
import oracle.sql.CHAR;
import oracle.sql.CharacterSet;
import oracle.sql.STRUCT;
import oracle.sql.StructDescriptor;

@SuppressWarnings("deprecation")
public class Cache {

	private static final Logger mLogger = LogManager.getLogger(Cache.class);

	private static Map<String, String> theTmpPricetermMapping = null;

	private static final String QUERY_ALL_MASKS = "{call c4Output.selectAllMasks (?)}";
	private static final String QUERY_PL_FOR_PRODUCTS = "{call c4util.selectPlAndPlatforms (?,?)} ";
	private static final String KEY_DELIMITER = "|";
	private static String STMT_PL_COMPANY_MAP = "SELECT PROD_LINE, TENANT_CD FROM T_PL_COMPANY";

	private static String sessionId;

	/**
	 * This Method is Used for Load All PL's & Tenant Codes HPI/HPE
	 * 
	 * @return
	 */
	public static Map<String, String> getPLToCompanyMap() throws C4SecurityException {

		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;
		HashMap<String, String> plCompanyMap = new HashMap<String, String>();
		try {

			conn = CdbConnectionMgr.getConnectionMgr().getConnection(DBConstants.C4_DBPOOL_C4PROD_ONSI);
			stmt = conn.createStatement();

			rs = stmt.executeQuery(STMT_PL_COMPANY_MAP);
			if (rs != null) {

				while (rs.next())
					plCompanyMap.put(rs.getString("PROD_LINE"), rs.getString("TENANT_CD"));

			}

		} catch (SQLException se) {
			mLogger.error("Exception occured at...." + se.getMessage());
			throw new C4SecurityException(se.getMessage());
		} catch (Exception e) {
			mLogger.error("Exception occured at...." + e.getMessage());
			throw new C4SecurityException(e.getMessage());
		} finally {
			try {
				if (conn != null)
					conn.close();

				if (stmt != null)
					stmt.close();

				if (rs != null)
					rs.close();
			} catch (Exception e) {
				mLogger.error("Exception occured at...." + e.getMessage());
				throw new C4SecurityException(e.getMessage());
			}
		}
		return plCompanyMap;
	}

	/**
	 * This Method is used for retrive the PL's & Platform info with respective
	 * Products Requested.
	 * 
	 * @param pProds
	 * @return
	 * @throws SQLException
	 */
	public static PlMappingData[] getPlMappingInfo(com.hp.c4.rsku.rSku.pojo.Product[] pProds) throws SQLException {
		SQLUtil util = null;
		Connection con = null;
		try {
			util = new SQLUtil(DBConstants.C4_DBPOOL_C4PROD_ONSI);
			con = util.getConnection();

			int oracleId = CharacterSet.US7ASCII_CHARSET;
			CharacterSet cset = CharacterSet.make(oracleId);

			StructDescriptor packDesc = StructDescriptor.createDescriptor("PACK_ID", con);
			ArrayList packIds = new ArrayList();
			util.setSQL(QUERY_PL_FOR_PRODUCTS);

			for (int i = 0; i < pProds.length; i++) {
				Object[] pack_attr = new Object[3];
				String opt = ((pProds[i].getOpt() == null || pProds[i].getOpt().trim().length() == 0) ? "<null>"
						: pProds[i].getOpt());
				String spn = ((pProds[i].getSpn() == null || pProds[i].getSpn().trim().length() == 0) ? "<null>"
						: pProds[i].getSpn());
				pack_attr[0] = new CHAR(pProds[i].getProdId(), cset);
				pack_attr[1] = new CHAR(opt, cset);
				pack_attr[2] = new CHAR(spn, cset);
				packIds.add(new STRUCT(packDesc, con, pack_attr));
			}
			ArrayDescriptor desc = ArrayDescriptor.createDescriptor("PACK_ARRAY", con);
			ARRAY new_array = new ARRAY(desc, con, packIds.toArray(new STRUCT[0]));
			util.setObject(1, new_array);
			util.registerOutParameter(2, oracle.jdbc.OracleTypes.ARRAY, "PL_PLATFORM_ARRAY");
			util.execute();
			Object[] theArray = (Object[]) util.getARRAY(2).getArray();
			PlMappingData[] thePlsData = new PlMappingData[theArray.length];
			for (int i = 0; i < theArray.length; i++) {
				STRUCT theStruct = (STRUCT) theArray[i];
				Object[] theAttributes = theStruct.getAttributes();
				final String thePl = (String) theAttributes[0];
				final String thePlatform = (String) theAttributes[1];
				thePlsData[i] = new PlMappingData(pProds[i], thePl, thePlatform);
			}
			return thePlsData;
		} finally {
			try {
				util.close();
			} catch (Exception ignore) {
				mLogger.error("Exception occured at...." + ignore.getMessage());
			}
		}
	}

	/**
	 * This method is used for loading all the Countries which relates to Geo_Codes
	 * & PriceDescriptors & Currency Code & Regions info
	 * 
	 * @return
	 */
	public static Map<String, DefaultPriceDescriptor> getAllDefaultCntryPriceDescriptors() throws C4SecurityException {
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;
		Map<String, DefaultPriceDescriptor> map = new LinkedHashMap<String, DefaultPriceDescriptor>();
		theTmpPricetermMapping = getAllPricetermMapping();

		try {
			conn = CdbConnectionMgr.getConnectionMgr().getConnection(DBConstants.C4_DBPOOL_C4PROD_ONSI);
			stmt = conn.createStatement();

			rs = stmt.executeQuery(DBConstants.COUNTRY_TO_PRICE_DESCRIPTORS);
			if (rs != null) {
				while (rs.next()) {
					String priceTermCode = rs.getString("PRICE_TERM_CODE");
					String defaultDelivery = theTmpPricetermMapping.get(priceTermCode);
					map.put(rs.getString("DESCRIPTION"),
							new DefaultPriceDescriptor(rs.getString("SN"), rs.getString("DESCRIPTION"),
									rs.getString("CODE"), rs.getString("PRICE_COUNTRY_CODE"),
									rs.getString("PRICE_CURRENCY_CODE"), priceTermCode, rs.getString("PARENT"),
									defaultDelivery));
				}
			}

		} catch (SQLException se) {
			mLogger.error("Exception occured at...." + se.getMessage());
			throw new C4SecurityException(se.getMessage());
		} catch (Exception e) {
			mLogger.error("Exception occured at...." + e.getMessage());
			throw new C4SecurityException(e.getMessage());
		} finally {
			try {
				if (conn != null)
					conn.close();

				if (rs != null)
					rs.close();
			} catch (Exception e) {
				mLogger.error("Exception occured at...." + e.getMessage());
				throw new C4SecurityException(e.getMessage());
			}
		}

		return map;
	}

	/**
	 * THis Method used for retive the PriceTermCode & Element Type Ex: DP,VTRDX
	 * 
	 * @return
	 */

	public static Map<String, String> getAllPricetermMapping() throws C4SecurityException {

		try {

			CpricetermMappingIO io = new CpricetermMappingIO(DBConstants.C4_DBPOOL_GPSNAP_ONSI);
			theTmpPricetermMapping = io.getAllPricetermMapping();

			mLogger.info("getAllPricetermMapping:" + theTmpPricetermMapping);
		} catch (C4Exception e) {
			mLogger.error("Exception occured at...." + e.getMessage());
			throw new C4SecurityException(e.getMessage());
		}
		return theTmpPricetermMapping;
	}

	/**
	 * This method is used for retrives the Deafult MOT
	 * 
	 * @return
	 */
	public static Map<String, DefaultMotTradingExpense> getDefaultMotExpense()  throws C4SecurityException {
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;
		Map<String, DefaultMotTradingExpense> dfaultMotMap = new HashMap<String, DefaultMotTradingExpense>();
		try {
			conn = CdbConnectionMgr.getConnectionMgr().getConnection(DBConstants.C4_DBPOOL_C4PROD_ONSI);
			stmt = conn.createStatement();

			rs = stmt.executeQuery(DBConstants.DEFAULT_MOT_TRADING_EXPENSE);
			if (rs != null) {
				while (rs.next()) {
					dfaultMotMap.put(rs.getString("TR_MOT"), new DefaultMotTradingExpense(rs.getString("TR_MOT"),
							rs.getString("ELEMENT_TYPE"), rs.getString("DEF_TREX")));
				}
			}
		} catch (SQLException se) {
			mLogger.error("Exception occured at...." + se.getMessage());
			throw new C4SecurityException(se.getMessage());
		} catch (Exception e) {
			mLogger.error("Exception occured at...." + e.getMessage());
			throw new C4SecurityException(e.getMessage());
		} finally {
			try {
				if (conn != null)
					conn.close();

				if (stmt != null)
					stmt.close();

				if (rs != null)
					rs.close();
			} catch (Exception e) {
				mLogger.error("Exception occured at...." + e.getMessage());
				throw new C4SecurityException(e.getMessage());
			}
		}
		return dfaultMotMap;

	}

	/**
	 * This method is used for Load ALL PL's Mask Elements Required/NotRequired
	 * Flags
	 * 
	 * @return
	 */
	public static Map<String, List<MaskElementTypes>> getAllPLMasks() throws C4SecurityException {

		SQLUtil theUtil = null;
		ResultSet rs = null;

		Map<String, List<MaskElementTypes>> theMasks = new HashMap<String, List<MaskElementTypes>>();
		try {
			theUtil = new SQLUtil(DBConstants.C4_DBPOOL_C4PROD_ONSI);

			theUtil.setSQL(QUERY_ALL_MASKS);
			theUtil.registerOutParameter(1, oracle.jdbc.OracleTypes.CURSOR);
			theUtil.execute();

			rs = (ResultSet) theUtil.getObject(1);

			while (rs.next()) {
				final String theKey = getMaskKey(rs.getString("PROD_LINE"), rs.getString("PLATFORM"));
				List<MaskElementTypes> theList = theMasks.get(theKey);
				MaskElementTypes theElemementType = new MaskElementTypes(rs.getString("ELEMENT_TYPE"),
						rs.getString("FLAG").charAt(0), rs.getString("FIELD_TYPE"));
				if (theList == null) {
					theList = new ArrayList<MaskElementTypes>();
					theMasks.put(theKey, theList);
				}
				theList.add(theElemementType);
			}

		} catch (SQLException e) {
			mLogger.error("Exception occured at...." + e.getMessage());
			throw new C4SecurityException(e.getMessage());
		} finally {
			try {
				if (rs != null)
					rs.close();
				if (theUtil != null)
					theUtil.close();
			} catch (Throwable ignore) {
				mLogger.error("Exception occured at...." + ignore.getMessage());
				throw new C4SecurityException(ignore.getMessage());
			}
		}
		return theMasks;
	}

	private static String getMaskKey(String pPl, String pPlatform) {
		StringBuffer buffer = new StringBuffer();

		buffer.append(pPl);
		if (pPlatform != null && !pPlatform.equalsIgnoreCase("<null>")) {
			buffer.append(KEY_DELIMITER);
			buffer.append(pPlatform);
		}

		return buffer.toString();
	}

	/**
	 * This Method is Used for Authenticate & generates the Session ID for PL
	 * permisssion Access
	 * 
	 * @param serviceAccountUserName
	 * @param serviceAccountPassword
	 * @param appName
	 * @return
	 * @throws C4SecurityException
	 */
	public static String validateUser(String serviceAccountUserName, String serviceAccountPassword, String appName)
			throws C4SecurityException {
		try {
			sessionId = new LoginDelegate().authenticate(serviceAccountUserName, serviceAccountPassword, appName);
		} catch (C4SecurityException e) {
			throw new C4SecurityException(e.getMessage());
		}

		return sessionId;
	}

	public static String[] getUpdateGeosForUser() {
		if (sessionId != null) {
			try {
				return (new UserDelegate()).getUpdateGeosForUser(sessionId);
			} catch (C4SecurityException e) {
				mLogger.error("Exception occured at...." + e.getMessage());
			}
		}
		return null;
	}

	public static String[] getUpdatePLsForUser() {
		if (sessionId != null) {
			try {
				return (new UserDelegate()).getUpdatePLsForUser(sessionId);
			} catch (C4SecurityException e) {
				mLogger.error("Exception occured at...." + e.getMessage());
			}
		}
		return null;
	}

	public static short[] getPermissions(String[] res) {
		if (sessionId != null) {
			try {
				return (new PermissionDelegate()).getPermissions(sessionId, res, "PL", "READ");
			} catch (C4SecurityException e) {
				mLogger.error("Exception occured at...." + e.getMessage());
			}
		}

		return null;
	}

	private static String getPlKeyId(String pl, Connection conn) {
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			stmt = conn.prepareStatement(DBConstants.SELECT_PL_KEY_ID);
			stmt.setString(1, pl);
			rs = stmt.executeQuery();
			if (rs != null) {
				if (rs.next()) {
					String keyId = rs.getString("KEY_ID");
					return keyId;
				}
			}
		} catch (SQLException se) {
			mLogger.error("Exception occured at...." + se.getMessage());
		} catch (Exception e) {
			mLogger.error("Exception occured at...." + e.getMessage());
		} finally {
			try {
				if (stmt != null)
					stmt.close();
				if (rs != null)
					rs.close();
			} catch (Exception e) {
				mLogger.error("Exception occured at...." + e.getMessage());
			}
		}
		return null;
	}

	/**
	 * This method is used for storing Rsku Info into T_PROD_HIER tables
	 * 
	 * @param productId
	 * @param pl
	 * @param prodDesc
	 * @return
	 */

	public static boolean storeRapidSkuDetails(String productId, String pl, String prodDesc) {
		boolean result = false;
		Connection conn = null;
		PreparedStatement stmt = null, stmt1 = null, stmt2 = null;
		ResultSet rs = null, rs1 = null;
		try {
			conn = CdbConnectionMgr.getConnectionMgr().getConnection(DBConstants.C4_DBPOOL_C4PROD_ONSI);

			stmt1 = conn.prepareStatement(DBConstants.SELECT_RSKU_AVLBLE_IN_DB);
			stmt1.setString(1, productId);
			rs1 = stmt1.executeQuery();
			if (rs1 != null && rs1.next()) {
				mLogger.info("RSKu product :" + productId + ": is already available in DB");
				String keyId = rs1.getString("PROD_LINE_KEY_ID");

				String PlKeyId = getPlKeyId(pl, conn);

				if (PlKeyId != null && keyId.equalsIgnoreCase(PlKeyId)) {
					result = true;
				}
			} else {
				String plKeyId = getPlKeyId(pl, conn);
				stmt = conn.prepareStatement(DBConstants.SELECT_T_PROD_HIER_MAX_NODE_ID);
				rs = stmt.executeQuery();
				if (rs != null && rs.next()) {
					Long nodeId = rs.getLong("MAX_VAL");
					mLogger.info("Insertion into Rsku Product details into C4 DB");
					++nodeId;
					// PROD_LINE_KEY_ID,PROD_ID,OPT,SPN,STATUS,PROD_DESC,SKU_DESC,TENANT_CD
					stmt2 = conn.prepareStatement(DBConstants.INSERT_RSKU_INTO_PROD_HIER);
					stmt2.setLong(1, nodeId);
					stmt2.setString(2, plKeyId);
					stmt2.setString(3, productId);
					stmt2.setString(4, "ACTIV");
					stmt2.setString(5, prodDesc);
					stmt2.setString(6, "HPI");

					int count = stmt2.executeUpdate();
					if (count > 0)
						result = true;
				}
			}
		} catch (SQLException se) {
			mLogger.error("Exception occured at...." + se.getMessage());
			return false;
		} catch (Exception e) {
			mLogger.error("Exception occured at...." + e.getMessage());
		} finally {
			try {
				if (conn != null)
					conn.close();
				if (stmt != null)
					stmt.close();
				if (rs != null)
					rs.close();
				if (stmt1 != null)
					stmt1.close();
				if (rs1 != null)
					rs1.close();
				if (stmt2 != null)
					stmt2.close();
			} catch (Exception e) {
				mLogger.error("Exception occured at...." + e.getMessage());
			}
		}
		return result;

	}

	/**
	 * CIFTX Cost, Insurance and Freight
	 * 
	 * @return
	 */

	public static HashMap<String, String> loadAllDeliveryMethods() {
		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		HashMap<String, String> deliveryMethodMap = new HashMap<String, String>();
		try {
			conn = CdbConnectionMgr.getConnectionMgr().getConnection(DBConstants.C4_DBPOOL_C4PROD_ONSI);

			stmt = conn.prepareStatement(DBConstants.SELECT_ALL_DELIVERY_METHODS);
			rs = stmt.executeQuery();
			while (rs != null && rs.next()) {
				deliveryMethodMap.put(rs.getString("ELEMENT_TYPE"), getDeliveryMethod(rs.getString("DESCRIPTION")));

			}
		} catch (Exception e) {
			mLogger.error("Exception occured at...." + e.getMessage());
		} finally {
			try {
				if (conn != null)
					conn.close();
				if (stmt != null)
					stmt.close();
				if (rs != null)
					rs.close();

			} catch (Exception e) {
				mLogger.error("Exception occured at...." + e.getMessage());
			}
		}
		return deliveryMethodMap;
	}

	/*
	 * method name : getDeliveryMethod Description : in argument : tempStr ->
	 * delivery method description from table t_element_type for ex.
	 * "Free CArrier  TRading  eXpense" . return for ex. "Free CArrier" .
	 */
	private static String getDeliveryMethod(String tempStr) {
		int ind = tempStr.indexOf("TRading");
		String retStr = tempStr;
		// if TRading is present, send first part of the description, else return
		// original string passed.
		if (ind > 0)
			retStr = tempStr.substring(0, ind).trim();
		return retStr;
	}

	/**
	 * This Method is used for Loading All MOT(Mode of Transports)
	 * 
	 * AIR RAIL TRUCK
	 * 
	 * @return
	 */

	public static HashMap<String, String> loadAllMots() throws C4SecurityException {
		Connection conn = null;
		PreparedStatement stmt = null;
		ResultSet rs = null;
		HashMap<String, String> motMap = new HashMap<String, String>();
		try {
			conn = CdbConnectionMgr.getConnectionMgr().getConnection(DBConstants.C4_DBPOOL_C4PROD_ONSI);

			stmt = conn.prepareStatement(DBConstants.MOT);
			rs = stmt.executeQuery();
			while (rs != null && rs.next()) {
				motMap.put(rs.getString("DESCRIPTION"), rs.getString("MOT"));

			}
		} catch (SQLException e) {
			mLogger.error("Exception occured at...." + e.getMessage());
			throw new C4SecurityException(e.getMessage());
		} finally {
			try {
				if (conn != null)
					conn.close();
				if (stmt != null)
					stmt.close();
				if (rs != null)
					rs.close();

			} catch (Exception e) {
				mLogger.error("Exception occured at...." + e.getMessage());
				throw new C4SecurityException(e.getMessage());
			}
		}
		return motMap;
	}

	/**
	 * This method is used for fetching Period Id's from T_PERIOD table
	 * 
	 * @param c4CostDates
	 * @return
	 */
	public static Map<Character, TPeriodData> findTPeriodIds(String[] c4CostDates) {

		mLogger.info("Get the C4 T_PERIOD id's from C4 Table......");
		ResultSet rs = null;
		PreparedStatement ps = null;
		Map<Character, TPeriodData> tPeriodMap = new LinkedHashMap<Character, TPeriodData>();
		try {
			Connection theConnection = CdbConnectionMgr.getConnectionMgr()
					.getConnection(DBConstants.C4_DBPOOL_C4PROD_ONSI);

			ps = theConnection.prepareStatement(DBConstants.FIND_SELECT_T_PERIOD_IDS);

			ps.setString(1, c4CostDates[0]);
			ps.setString(2, c4CostDates[1]);
			ps.setString(3, c4CostDates[2]);
			rs = ps.executeQuery();
			while (rs.next()) {

				Integer periodId = rs.getInt("PERIOD_ID");
				String periodType = rs.getString("PERIOD_TYPE");
				Date startDate = rs.getDate("START_DATE");
				TPeriodData tperiod = new TPeriodData(periodId, periodType.charAt(0), startDate);
				tPeriodMap.put(periodType.charAt(0), tperiod);
				if (tPeriodMap.size() == 3)
					break;
			}

		} catch (Exception e) {
			mLogger.error("Exception occured at fetching C4 Period ID's...." + e.getMessage());
		}
		return tPeriodMap;
	}
}
