package com.quizapp.tollywood.serverutils;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.http.Header;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import com.quizapp.tollywood.QuizApp;
import com.quizapp.tollywood.User;
import com.quizapp.tollywood.UserDeviceManager;
import com.quizapp.tollywood.appcontrollers.ProgressiveQuizController;
import com.quizapp.tollywood.appcontrollers.ProgressiveQuizController.UserAnswer;
import com.quizapp.tollywood.configuration.Config;
import com.quizapp.tollywood.databaseutils.Badge;
import com.quizapp.tollywood.databaseutils.Category;
import com.quizapp.tollywood.databaseutils.Feed;
import com.quizapp.tollywood.databaseutils.OfflineChallenge;
import com.quizapp.tollywood.databaseutils.OfflineChallenge.ChallengeData;
import com.quizapp.tollywood.databaseutils.Question;
import com.quizapp.tollywood.databaseutils.Quiz;
import com.quizapp.tollywood.databaseutils.UserInboxMessage;
import com.quizapp.tollywood.datalisteners.DataInputListener;
import com.quizapp.tollywood.datalisteners.DataInputListener2;
import com.quizapp.tollywood.serverutils.ServerResponse.MessageType;
import com.quizapp.tollywood.uiutils.UiUtils.UiText;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;
import com.loopj.android.http.SyncHttpClient;

import de.tavendo.autobahn.WebSocketException;
import de.tavendo.autobahn.WebSocketHandler;

class Item<T> {
    int reletiveProb;
    T name;
    
    Item(int prob, T n){
    	reletiveProb = prob;
    	name = n;
    }
}
class RandomSelector <T>{
    List<Item<T>> items = new ArrayList<Item<T>>();
    Random rand = new Random();
    int totalSum = 0;

    RandomSelector(List<Item<T>> items) {
    	this.items = items;
        for(Item<T> item : items) {
            totalSum = totalSum + item.reletiveProb;
        }
    }

    public Item<T> getRandom() {

        int index = rand.nextInt(totalSum);
        int sum = 0;
        int i=0;
        while(sum < index ) {
             sum = sum + items.get(i++).reletiveProb;
        }
        return items.get(i==0?0:i-1);
    }
}

public class ServerCalls {

	public static final String SERVER_ADDR = "http://quizapp.appsandlabs.com";

	public static final String CDN_PATH = "https://storage.googleapis.com/quizapp-tollywood/";

	public double lastLoginTime = 0d;

	private HashMap<String, String> serverMap;
	
	
	private int serverErrorMsgShownCount =0;
	//encodedKey=YWJjZGVmZ2h8YWJoaW5hdmFiY2RAZ21haWwuY29t|1393389556|37287ef4a1261b927e8a98d639035d81f0e7eb2c
	public AsyncHttpClient client = null;
	private  SyncHttpClient sClinet;

	private QuizApp quizApp;
	private RandomSelector<String> randomServerSelector;
	//public static DatabaseHelper dbhelper = Config.getDbhelper();
	public ServerCalls(QuizApp quizApp){
		this.quizApp = quizApp;
		client  = new AsyncHttpClient();
		client.setMaxRetriesAndTimeout(3, 10);
		sClinet = new SyncHttpClient();
		serverMap = new HashMap<String, String>();
		serverMap.put("master", SERVER_ADDR);
		setSeverMap(serverMap);
	}
	
	
	public  HashMap<String,Object> decodeConfigVariables(ServerResponse response){
		if(response == null ) return null;
		HashMap<String,Object> map = decodeConfigVariables(response.payload10);
		if(map!=null && map.containsKey(Config.PREF_SERVER_TIME)){
			quizApp.getConfig().setServerTime((Double)map.get(Config.PREF_SERVER_TIME) , response.getResponseTime());
		}
		return map;
	}

	
	public HashMap<String, Object> decodeConfigVariables(String fromRawJson){
		HashMap<String,Object> map = null;
		if(fromRawJson==null || fromRawJson.equalsIgnoreCase(""))
			return null;
		try{ 
			map = quizApp.getConfig().getGson().fromJson(fromRawJson , new TypeToken<HashMap<String,Object>>(){}.getType());
		}
		catch(IllegalStateException e){
			e.printStackTrace();
			return null;
		}
		if(map.containsKey(Config.FORCE_APP_VERSION)){ 
			try{
				Context context = quizApp.getContext();
				PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
				int versionCode = pInfo.versionCode;
				if(versionCode < (Integer)map.get(Config.FORCE_APP_VERSION)){
					quizApp.addUiBlock("Please Upgrade immutable");
				}
			}
			catch(Exception e){
				
			}
		}

		return map;
	}

	
	 
	public void handleResponseCodes(MessageType code, ServerResponse response){
		if(code==null){
			return;
		}
		decodeConfigVariables(response);
		switch(code){
			case FAILED:
				if(serverErrorMsgShownCount++%2==0)
					quizApp.getStaticPopupDialogBoxes().yesOrNo(UiText.SERVER_ERROR.getValue(), UiText.OK.getValue(), UiText.CANCEL.getValue() , null);
				break;
			case NOT_AUTHORIZED:
				quizApp.getStaticPopupDialogBoxes().yesOrNo(UiText.SERVER_ERROR.getValue(), UiText.OK.getValue(), UiText.CANCEL.getValue(), new DataInputListener<Boolean>(){
					@Override
					public String onData(Boolean s) {
						if(s){
							quizApp.getUserDeviceManager().clearUserPreferences();
							quizApp.reinit(true);
						}
						return null;
					}
				});
				
		default:
			break;
		}
	}
	public void makeServerCall(final String url,final ServerNotifier serverNotifier,final boolean blockUi){
	      makeServerCall(url, serverNotifier, blockUi, false);
	}
	public void makeServerCall(final String url,final ServerNotifier serverNotifier,final boolean blockUi , boolean sync){
				final long nano1 = Config.getCurrentNanos();
				final AsyncHttpClient c = sync ? sClinet : client;
				c.get(url, new AsyncHttpResponseHandler() {
					int retryCount = 0;

					@Override
					public void onStart() {
						if (blockUi) {
							quizApp.addUiBlock();
							//open a loadingDialogue
						}
					}

					@Override
					public void onSuccess(int arg0, Header[] arg1, byte[] responseBytes) {
						String response = new String(responseBytes);
						ServerResponse serverResponse = quizApp.getConfig().getGson().fromJson(response, ServerResponse.class);
						serverResponse.setResponseTime(Config.getCurrentNanos() - nano1);
						MessageType messageType = serverResponse.getStatusCode();
						handleResponseCodes(messageType, serverResponse);
						serverNotifier.onServerResponse(messageType, serverResponse);
					}

					public void onFailure(int messageType, org.apache.http.Header[] headers, byte[] responseBody, Throwable error) {

						serverNotifier.onServerResponse(MessageType.FAILED, null);
						if (this.retryCount++ < Config.RETRY_URL_COUNT)
							c.get(url, this); // retry Once More
						else
							handleResponseCodes(MessageType.FAILED, null);
					}

					@Override
					public void onFinish() {
						if (blockUi) {
							//loadingDialogue.dismiss();
							quizApp.getUiUtils().removeUiBlock();
						}
					}
				});
	}

	public  void makeGeneralServerPostCall(String url, Map<String,String> postData ,
			final HashMap<String,String> headers,
			final DataInputListener<String> listener,final boolean blockUi){
			SyncHttpClient client = new SyncHttpClient();
			if(headers!=null || headers.size()>0){
				for(String key: headers.keySet())
					client.addHeader(key, headers.get(key));
			}
			client.post(url, new RequestParams(postData), new AsyncHttpResponseHandler() {
				@Override
				 public void onStart() {
					if(blockUi){
						quizApp.addUiBlock();
						
						}
				}
				@Override
				public void onSuccess(int arg0, Header[] arg1, byte[] responseBytes) {
					String response = new String(responseBytes);					
				    listener.onData(response);
				}
				public void  onFailure(int messageType, org.apache.http.Header[] headers, byte[] responseBody, Throwable error){
					listener.onData(null);
				}
				 @Override
				public void onFinish() {
				    	if(blockUi){
				    		quizApp.removeUiBlock();							
				    	}
				}  
				}
		);
	}
		
	public  void makeServerPostCall(String url, Map<String,String> postData ,final ServerNotifier serverNotifier,final boolean blockUi){

		final long nano1 = Config.getCurrentNanos();
		client.post(url, new RequestParams(postData), new AsyncHttpResponseHandler() {
				@Override
				 public void onStart() {
					if(blockUi){
						quizApp.addUiBlock();

						}
				}
				@Override
				public void onSuccess(int arg0, Header[] arg1, byte[] responseBytes) {
					String response = new String(responseBytes);					
				    ServerResponse serverResponse= quizApp.getConfig().getGson().fromJson(response, ServerResponse.class);
				    MessageType messageType = serverResponse.getStatusCode();
				    serverResponse.setResponseTime(Config.getCurrentNanos() - nano1);
				    
				    handleResponseCodes(messageType , serverResponse);
				    serverNotifier.onServerResponse(messageType , serverResponse);
				}
				public void  onFailure(int messageType, org.apache.http.Header[] headers, byte[] responseBody, Throwable error){
					serverNotifier.onServerResponse(MessageType.FAILED , null);
				    handleResponseCodes(MessageType.FAILED,null);

				}
				 @Override
				public void onFinish() {
				    	if(blockUi){
				    		quizApp.removeUiBlock();							
				    	}
				}  
				}
		);
	}

	public static String mapToJson(Map <String,String> map){
		String ret ="{";
		for(Object k : map.keySet()){
			ret+=(k+":"+map.get(k)+",");
		}
		return ret.substring(0,ret.length()-1)+"}";
	}
	
	
	public void makeServerCall(String url, ServerNotifier serverNotifier){
		makeServerCall(url, serverNotifier, false);
	}	
	
	public void makeServerPostCall(String url, HashMap<String, String> postData , ServerNotifier serverNotifier){
		makeServerPostCall(url,postData,serverNotifier,false);
	}
	public String getAServerAddr(){
		return randomServerSelector.getRandom().name;
	}
	public String getMasterServerAddr(){
		return serverMap.get("master");
	}
	
	public void getEncodedKey(final String deviceId, final String phoneNumber, ServerNotifier serverNotifier) {
		String url = getAServerAddr()+"/func?task=getEncodedKey";
		url+="&deviceId="+deviceId+"&phoneNumber="+phoneNumber;
		makeServerCall(url, serverNotifier, true);
	}
	


	public static void setUserGCMKey(final Context context, String registrationId, final DataInputListener<Boolean> dataInputListener) {
		String url = SERVER_ADDR+"/func?task=setGCMRegistrationId"; 
		url+="&encodedKey="+UserDeviceManager.getEncodedKey(context)+"&regId="+registrationId;
		
		AsyncHttpClient client  = new AsyncHttpClient();
		client.setMaxRetriesAndTimeout(3, 10);

		final ServerNotifier serverNotifier = new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch(messageType){
					case REG_SAVED:
						if(dataInputListener!=null){
							dataInputListener.onData(true);
						}
						break;
					case FAILED:
						if(dataInputListener!=null){
							dataInputListener.onData(false);
						}
						break;
					default:
						break;
				}
			}
		};
		client.get(url, new AsyncHttpResponseHandler() {
			@Override
			public void onSuccess(int arg0, Header[] arg1, byte[] responseBytes) {
				String response = new String(responseBytes);
			    ServerResponse serverResponse= (new Gson()).fromJson(response, ServerResponse.class);
			    MessageType messageType = serverResponse.getStatusCode();
			    serverNotifier.onServerResponse(messageType , serverResponse);
			}
			public void  onFailure(int messageType, org.apache.http.Header[] headers, byte[] responseBody, Throwable error){
				
				serverNotifier.onServerResponse(MessageType.FAILED , null);
			} 
		});
		
	}

	public static void unsetUserGCMKey(Context context, String registrationId) {
		
	}

	
	public void getUserByUid(String uid , final DataInputListener<User> dataInputListener) {
		String url = getAServerAddr()+"/func?task=getUserByUid";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&uid="+uid;
		makeServerCall(url,new ServerNotifier() {			
		@Override
		public void onServerResponse(MessageType messageType, ServerResponse response) {
			switch(messageType){
				case OK_USER_INFO:
					if(dataInputListener!=null){
						User user = quizApp.getConfig().getGson().fromJson(response.payload,User.class);
						quizApp.cachedUsers.put(user.uid , user);
						quizApp.getDataBaseHelper().saveUser(user);
						dataInputListener.onData(user);
					}
					break;
				case FAILED: 
					if(dataInputListener!=null){
						dataInputListener.onData(null);
					}
					break;
				}
			}
		},false); 
	}		

	public void updateUserRating(final float rating, final DataInputListener<Boolean> dataInputListener) {
		String url = getAServerAddr()+"/func?task=updateUserRating";
		url+="&rating="+rating;
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		makeServerCall(url,new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType,ServerResponse response) {
				switch(messageType){
					case RATING_OK:
						quizApp.getUserDeviceManager().setDoublePreference(Config.PREF_APP_RATING, rating);
						dataInputListener.onData(true);
						break;
					default:
						break;
				}
			}
		});
	}
	
	
	public void getPreviousOfflineChallenges(int lastLoginIndex){
		
	}
	
	public void getPreviousFeed(int lastLoginIndex){
	}
	
	public static void clearAllStaticVariables() {
	}

	public void getAllUpdates(final DataInputListener2<List<Feed> ,List<UserInboxMessage> ,List<OfflineChallenge>, Boolean> onFinishListener) {


		String url = getAServerAddr()+"/func?task=getAllUpdates";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		final boolean isLogin;
		if(quizApp.getConfig().getCurrentTimeStamp() - lastLoginTime  >3600){// is login true
			isLogin = true;
			lastLoginTime = quizApp.getConfig().getCurrentTimeStamp();
			url+="&isLogin=true";
			if(quizApp.getUserDeviceManager().hasJustInstalled){
				url+="&isFirstLogin=true";
			}
			url+="&maxQuizTimestamp="+Double.toString(quizApp.getDataBaseHelper().getMaxTimeStampQuiz());
			url+="&maxBadgesTimestamp="+Double.toString(quizApp.getDataBaseHelper().getMaxTimeStampBadges());
			url+="&lastOfflineChallengeIndex="+quizApp.getDataBaseHelper().getLastChallengeIndex();
			url+="&lastSeenTimestamp="+Double.toString(quizApp.getUserDeviceManager().lastActiveTime);
		}
		else{
			isLogin = false;
		}
		
		
		makeServerCall(url, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch(messageType){
					case OK_UPDATES:
						if(isLogin){
							quizApp.setUser(quizApp.getConfig().getGson().fromJson(response.payload7 , User.class));//set the user here
							List<Quiz> quizzes = quizApp.getConfig().getGson().fromJson(response.payload, new TypeToken<List<Quiz>>(){}.getType());
							List<Category> categories = quizApp.getConfig().getGson().fromJson(response.payload1, new TypeToken<List<Category>>(){}.getType());
							List<Badge> badges = quizApp.getConfig().getGson().fromJson(response.payload2, new TypeToken<List<Badge>>(){}.getType());							
							
							for(Quiz q : quizzes){
								try {
//									if(quizApp.getUser().getStats().containsKey(q.quizId))
//										q.userXp = quizApp.getUser().getStats().get(q.quizId);
									quizApp.getDataBaseHelper().getQuizDao().createOrUpdate(q);
								} catch (SQLException e) {
									e.printStackTrace();
								}
							}
							
							for(Category c : categories){
								try {
									quizApp.getDataBaseHelper().getCategoryDao().createOrUpdate(c);
								} catch (SQLException e) {
									e.printStackTrace();
								}
							}
							for( Badge b : badges){
								try {
									quizApp.getDataBaseHelper().getBadgesDao().createOrUpdate(b);
								} catch (SQLException e) {
									e.printStackTrace();
								}
							}
						}
						List<Feed> userFeeds = quizApp.getConfig().getGson().fromJson(response.payload3, new TypeToken<List<Feed>>(){}.getType());
						List<UserInboxMessage> inboxMessages = quizApp.getConfig().getGson().fromJson(response.payload4, new TypeToken<List<UserInboxMessage>>(){}.getType());
						List<OfflineChallenge> offlineChallenges = quizApp.getConfig().getGson().fromJson(response.payload5, new TypeToken<List<OfflineChallenge>>(){}.getType());
						if(response.payload6!=null && !response.payload6.trim().equalsIgnoreCase("")){
							HashMap<String,String> serverMap = quizApp.getConfig().getGson().fromJson(response.payload6, new TypeToken<HashMap<String,String>>(){}.getType());
							setSeverMap(serverMap);
						}
						if(response.payload8!=null){
							// this is users list of conversed people
							List<String> uids = quizApp.getConfig().getGson().fromJson(response.payload8, new TypeToken<List<String>>(){}.getType());
							HashMap<String , Integer> uidUnseenMessages = new HashMap<String, Integer>();
							for(String uid : uids){ 
								if(uidUnseenMessages.containsKey(uid)){
									uidUnseenMessages.put(uid, uidUnseenMessages.get(uid)+1);
								}
								else{
									uidUnseenMessages.put(uid, 1);
								}
							}
							for(Entry<String, Integer> uid: uidUnseenMessages.entrySet()){
								quizApp.getDataBaseHelper().setRecentChat(uid.getKey(), null, uid.getValue());//just 
							}
							
						}
						if(quizApp.getUserDeviceManager().getPreference(Config.PREF_IS_FIRST_TIME_LOAD,null)==null)
							quizApp.getUserDeviceManager().setPreference(Config.PREF_IS_FIRST_TIME_LOAD, "false");
						onFinishListener.onData(userFeeds, inboxMessages, offlineChallenges, true);
						break;
					default:
						onFinishListener.onData(null, null, null,false);
						break;
				}
			}
		},false);
	}


	protected void setSeverMap(HashMap<String, String> serverMap) {
		this.serverMap = serverMap;
		List<Item<String>> temp = new ArrayList<Item<String>>();
		for(String key : serverMap.keySet()){
			if(key.equalsIgnoreCase("master")){ //lower weightage for master server if we may want 
				temp.add(new Item<String>(20, serverMap.get(key)));
			}
			else{
				temp.add(new Item<String>(20, serverMap.get(key)));
			}
		}
		this.randomServerSelector = new RandomSelector<String>(temp);
	}


	public void checkVerificationStatus(DataInputListener<String> dataInputListener) {
		// TODO Auto-generated method stub
		
	}


	public void doGooglePlusLogin(final User user,final DataInputListener<User> loginListener) {
		quizApp.addUiBlock(UiText.REGISTERING_USER.getValue());
		user.deviceId = quizApp.getUserDeviceManager().getDeviceId();
		String url = getAServerAddr()+"/func?task=registerWithGoogle";
		Map<String,String > params = new HashMap<String, String>();
		params.put("userJson",quizApp.getConfig().getGson().toJson(user));
		if(quizApp.getUser()!=null){ // connect mode
			params.put("connectUid", quizApp.getUser().uid);
		}
		makeServerPostCall(url, params, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				quizApp.removeUiBlock();
//				User user = null;
				switch(messageType){
					case GPLUS_USER_SAVED:
//						user = quizApp.getConfig().getGson().fromJson(response.payload,User.class);
						quizApp.getUserDeviceManager().setPreference(Config.PREF_ENCODED_KEY, response.payload);
						loginListener.onData(user);
						break;
					default:
//						 user = quizApp.getConfig().getGson().fromJson(response.payload,User.class);
						loginListener.onData(null);
						break;
				}
			}
		},true);
	}


	public void doFacebookLogin(final User user, final DataInputListener<User> loginListener) {
		quizApp.addUiBlock();
		user.deviceId = quizApp.getUserDeviceManager().getDeviceId();
		String url = getAServerAddr()+"/func?task=registerWithFacebook";
		Map<String,String > params = new HashMap<String, String>();
		params.put("userJson",quizApp.getConfig().getGson().toJson(user));
		if(quizApp.getUser()!=null){ // connect mode
			params.put("connectUid", quizApp.getUser().uid);
		}
		makeServerPostCall(url, params, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				//User user = null;
				quizApp.removeUiBlock();
				switch(messageType){
					case FACEBOOK_USER_SAVED:
						//user = quizApp.getConfig().getGson().fromJson(response.payload,User.class);
						quizApp.getUserDeviceManager().setPreference(Config.PREF_ENCODED_KEY, response.payload);
						loginListener.onData(user);
						break;
					default:
						//user = quizApp.getConfig().getGson().fromJson(response.payload,User.class);
						loginListener.onData(null);
						break;
				}
			}
		},true);
	}
	
	
	private void startProgressiveQuiz(String webSocketAddr , final ProgressiveQuizController pController , final Quiz quiz, String serverId, HashMap<String,String> additionalParams){
		  String wsuri = webSocketAddr +"/progressiveQuiz";
		  wsuri+="?encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		  wsuri+="&quizId="+quiz.quizId;
		  if(additionalParams!=null)
			  wsuri+="&"+mapToQuery(additionalParams);
		  if(mConnection!=null && mConnection.isConnected()){
			  mConnection.disconnect();
		  }
		  mConnection = new ServerWebSocketConnection(serverId , serverMap.get(serverId)){
			  @Override
			  public void disconnect() {
				  super.disconnect();
			  }
		  };
			  
	    try {
	       mConnection.connect(wsuri, new WebSocketHandler() {
	
	          @Override
	          public void onOpen() { 
	             pController.startSocketConnection(mConnection , quiz);
	          } 
	
	          @Override
	          public void onTextMessage(String data) {
	          	ServerResponse s = quizApp.getConfig().getGson().fromJson(data, ServerResponse.class);
	          	MessageType messageType = s.getMessageType();
	          	pController.onMessageRecieved(messageType , s , data);
	          }
	
	          @Override
	          public void onClose(int code, String reason) {//Server error 426 (Upgrade Required)
	             Log.d("autobahn", "Connection lost.");
	             pController.onSocketClosed();
	          }
	       });
	    } catch (WebSocketException e) {
	       Log.d("autobahn", e.toString());
	    }
	}
	
	private String mapToQuery(HashMap<String , String> queryString){
		StringBuilder sb = new StringBuilder();
		  for(HashMap.Entry<String, String> e : queryString.entrySet()){
		      if(sb.length() > 0){
		          sb.append('&');
		      }
		      try {
				sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
			} catch (UnsupportedEncodingException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		  }
		  return sb.toString();
	}

	
	
	 ServerWebSocketConnection mConnection = null;
	 
	 public void startProgressiveQuiz(final ProgressiveQuizController pController, final Quiz quiz, int quizType ,final HashMap<String,String> additionalParams , String serverId) {//p reselected server id
		 URI uri;
			try {
				uri = new URI(serverMap.get(serverId));
				String portString =  uri.getPort()>0 ? (":"+uri.getPort()) : "";
				startProgressiveQuiz("ws://"+uri.getHost()+portString , pController, quiz , serverId, additionalParams);
			} catch (URISyntaxException e) {
				e.printStackTrace();
			}
	 }
	 
	 public void startProgressiveQuiz(final ProgressiveQuizController pController, final Quiz quiz, int quizType ,final HashMap<String,String> additionalParams) {
		 String url = getMasterServerAddr()+"/func?task=getServer";
		 url+="&quizId="+quiz.quizId+"&quizType="+quizType;
		 
		 url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
			
		  makeServerCall(url , new ServerNotifier(){
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch(messageType){
					case OK_SERVER_DETAILS:
						String sid= response.payload1;
						String addr = response.payload2;
						if(!serverMap.containsKey(sid)){
							//WTFFFF
							serverMap.put(sid, addr);
							setSeverMap(serverMap);
						}
						URI uri;
						try {
							uri = new URI(response.payload2);
							String portString =  uri.getPort()>0 ? (":"+uri.getPort()) : "";
							startProgressiveQuiz("ws://"+uri.getHost()+portString , pController, quiz , sid, additionalParams);
						} catch (URISyntaxException e) {
							e.printStackTrace();
						}
					break;
					default:
						pController.ohNoDammit();
				}
			}
		});
	 }


	public void informActivatingBot(Quiz quiz, String sid) {
		String url =getMasterServerAddr()+"/func?task=activatingBotPQuiz&quizId="+quiz.quizId+"&sid="+sid;
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		makeServerCall(url , new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
			}
		});
	}


	public void getMessages(User user2, int toIndex, final DataInputListener<List<UserInboxMessage>> dataInputListener) {
		String url = getAServerAddr()+"/func?task=getPreviousMessages";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey()+"&toIndex="+toIndex+"&uid2="+user2.uid;
		makeServerCall(url, new ServerNotifier() {

			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch (messageType) {
					case OK_MESSAGES:
						List<UserInboxMessage> messages = quizApp.getConfig().getGson().fromJson(response.payload, new TypeToken<List<UserInboxMessage>>() {
						}.getType());
						dataInputListener.onData(messages);
						break;
					default:
						dataInputListener.onData(new ArrayList<UserInboxMessage>());
						break;
				}
			}
		});
	}


	public void sendChatMessage(User user2, String string , final DataInputListener<Boolean> isSuccessful) {
		String url = getAServerAddr()+"/func?task=sendInboxMessages";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();

		Map<String,String > params = new HashMap<String, String>();
		params.put("toUser",user2.uid);
		params.put("textMessage",string);
		makeServerPostCall(url, params , new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType,ServerResponse response) {
				switch(messageType){
					case OK_SEND_MESSAGE:
					   isSuccessful.onData(true);
					   break;
					default:
          			   isSuccessful.onData(true);
          			   break;
				}
			}
		}, false);
	}

	public void getUids(List<String> users, final DataInputListener<List<User>> usersInfo, boolean sync) {
		String url = getAServerAddr()+"/func?task=getUsersInfo";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		HashMap<String , String> params = new HashMap<String, String>();
 		
		params.put("uidList",quizApp.getConfig().getGson().toJson(users));
		
		makeServerPostCall(url, params, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch (messageType) {
					case OK_USERS_INFO:
						usersInfo.onData((List<User>) quizApp.getConfig().getGson().fromJson(response.payload, new TypeToken<List<User>>() {
						}.getType()));
						return;
					default:
						usersInfo.onData(null);
						return;
				}
			}
		}, false);
	}


	public void getScoreBoards(String quizId , final DataInputListener2<HashMap<String, Integer[]>, HashMap<String, Integer[]>, Void, Void> localGlobalRanksDataListener) {
		String url = getAServerAddr()+"/func?task=getLeaderboards";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&quizId="+quizId;
		makeServerCall(url, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch (messageType) {
					case OK_SCORE_BOARD:
						localGlobalRanksDataListener.onData((HashMap<String, Integer[]>) quizApp.getConfig().getGson().fromJson(response.payload, new TypeToken<HashMap<String, Integer[]>>() {
						}.getType()),
								(HashMap<String, Integer[]>) quizApp.getConfig().getGson().fromJson(response.payload1, new TypeToken<HashMap<String, Integer[]>>() {
								}.getType()), null);
						break;
				}
			}
		});
	}

	/* This will update the quiz history db too */
	public void updateQuizWinStatus(String quizId, int winStatus , double newPoints, User otherUser , String userAnswers1Json , String userAnswers2Json) {
		String url = getAServerAddr()+"/func?task=updateQuizWinStatus";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&quizId="+quizId;
		url+="&xpPoints="+newPoints;
		url+="&winStatus="+winStatus+"";
		url+="&uid2="+otherUser.uid;
		

		HashMap<String, String> params = new HashMap<String, String>();
		String userAnswersJson;
		if(userAnswers1Json!=null){
			params.put("userAnswers1",userAnswers1Json);
		}
		if(userAnswers2Json!=null){
			params.put("userAnswers2",userAnswers2Json);
		}
		
		
		

		makeServerPostCall(url, params, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch (messageType) {
					case OK:
						break;
				}
			}
		});
		//update history item
	}

	// does not work
	public void getUserByUidSync(Object key, final DataInputListener<Boolean> dataInputListener) {
		// TODO Auto-generated method stub
		final String uid = key.toString();
		String url = getAServerAddr()+"/func?task=getUserById";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&uid="+uid;
		makeServerCall(url,new ServerNotifier() {			
		@Override
		public void onServerResponse(MessageType messageType, ServerResponse response) {
			switch(messageType){
				case OK_USER_INFO:
					if(dataInputListener!=null){
						quizApp.cachedUsers.put(uid, quizApp.getConfig().getGson().fromJson(response.payload,User.class));
					}
					break;
				case FAILED: 
					if(dataInputListener!=null){
						dataInputListener.onData(null);
					}
					break;
				}
			}
		},true, true);//sync 
	}
	
	public void getOfflineChallenge(String offlineChallengeId, final DataInputListener<OfflineChallenge> dataInputListener) {
		// TODO Auto-generated method stub
		String url = getAServerAddr()+"/func?task=getOfflineChallengeById";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&offlineChallengeId="+offlineChallengeId;
		makeServerCall(url,new ServerNotifier() {			
		@Override
		public void onServerResponse(MessageType messageType, ServerResponse response) {
			OfflineChallenge offlineChallenge;
			switch(messageType){
				case OK_CHALLENGES:
					offlineChallenge = quizApp.getConfig().getGson().fromJson(response.payload, OfflineChallenge.class);
					quizApp.getDataBaseHelper().updateOfflineChallenge(offlineChallenge);
					if(dataInputListener!=null)
						dataInputListener.onData(offlineChallenge);
					break;
				case FAILED: 
					if(dataInputListener!=null){
						dataInputListener.onData(null);
					}
					break;
				}
			}
		},false);//sync 
	}

	private HashMap<String, Boolean> subScribingRequests = new HashMap<String, Boolean>();
	
	public void subscribeTo(final User user2 ,final DataInputListener<Boolean> listener) {
		if(subScribingRequests.containsKey(user2.uid)){
			if(listener!=null) listener.onData(true);
		}
		String url = getAServerAddr()+"/func?task=subscribeTo";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&uid2="+user2.uid;
		makeServerCall(url, new ServerNotifier() {
		@Override
		public void onServerResponse(MessageType messageType,ServerResponse response) {
			switch(messageType){
				case OK:
					if(listener!=null){
						listener.onData(true);
					}
					break;
				default:
					subScribingRequests.remove(user2.uid);
					if(listener!=null){
						listener.onData(false);
					}
					break;
			}
		}
		}, true);
	}
	
	public void unSubscribeTo(final User user2 ,final DataInputListener<Boolean> listener) {
		String url = getAServerAddr()+"/func?task=unSubscribeTo";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&uid2="+user2.uid;
		makeServerCall(url, new ServerNotifier() {
		@Override
		public void onServerResponse(MessageType messageType,ServerResponse response) {
			switch(messageType){
				case OK:
					if(listener!=null){
						listener.onData(true);
					}
					break;
				default:
					if(listener!=null){
						listener.onData(false);
					}
					break;
			}
		}
		}, true);
	}

	public void addBadges(List<String> badgeIds, final DataInputListener<Boolean> listener) {
		String url = getAServerAddr()+"/func?task=addBadges";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		HashMap<String, String> params = new HashMap<String, String>();
		params.put("badgeIds",quizApp.getConfig().getGson().toJson(badgeIds));
		
		makeServerPostCall(url, params, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch (messageType) {
					case OK:
						if (listener != null) {
							listener.onData(true);
						}
						break;
					default:
						if (listener != null) {
							listener.onData(false);
						}
						break;
				}
			}
		});
	}


	public void searchUsersByName(String currentSearchQuery , final DataInputListener<List<User>> listener) {
		String url = getAServerAddr()+"/func?task=searchByUserName";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&searchQ="+currentSearchQuery;
		makeServerCall(url, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch (messageType) {
					case OK:
						List<User> users = quizApp.getConfig().getGson().fromJson(response.payload, new TypeToken<List<User>>() {
						}.getType());
						for (User user : users) {
							user.isFriend = false;
						}
						listener.onData(users);
						break;
					default:
						break;
				}
			}
		});
	}


	public void addOfflineChallange(Quiz quiz, User otherUser,	List<UserAnswer> userAnswers, String offlineChallengeId, final DataInputListener<OfflineChallenge> dataInputListener) {
			
			String url = getAServerAddr()+"/func?task=addOfflineChallenge";
			url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
			url+="&uid2="+otherUser.uid;
			url+="&offlineChallengeId="+offlineChallengeId;
			Map<String,String > params = new HashMap<String, String>(); 
			params.put("challengeData",quizApp.getConfig().getGson().toJson(new ChallengeData(quiz.quizId, userAnswers)));
			makeServerPostCall(url, params, new ServerNotifier() {
				@Override
				public void onServerResponse(MessageType messageType, ServerResponse response) {
					switch (messageType) {
						case OK:
							dataInputListener.onData(quizApp.getConfig().getGson().fromJson(response.payload, OfflineChallenge.class));
							break;
						default:
							dataInputListener.onData(null);
							break;
					}
				}
			}, false);
	}


	public void loadQuestionsInOrder(ArrayList<String> questionIds, final DataInputListener<List<Question>> dataInputListener) {
		
		String url = getAServerAddr()+"/func?task=loadQuestionsInOrder";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		Map<String,String > params = new HashMap<String, String>(); 
		params.put("questionIds",quizApp.getConfig().getGson().toJson(questionIds));
		makeServerPostCall(url, params, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
					switch(messageType){
						case OK_QUESTIONS:
							dataInputListener.onData((List<Question>) quizApp.getConfig().getGson().fromJson(response.payload, new TypeToken<List<Question>>(){}.getType())); 
							break;
						default:
							dataInputListener.onData(null);
							break;
					}
			}
		}, false);
	}


	public void completeOfflineChallenge(String offlineChallengeId, ChallengeData challengeData2, final DataInputListener<Boolean> dataInputListener) {

		String url = getAServerAddr()+"/func?task=onOfflineChallengeCompleted";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&offlineChallengeId="+offlineChallengeId;
		Map<String,String > params = new HashMap<String, String>(); 
		params.put("challengeData",quizApp.getConfig().getGson().toJson(challengeData2));
		makeServerPostCall(url, params, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
					switch(messageType){
						case OK:
							dataInputListener.onData(true);
							break;
						default:
							dataInputListener.onData(false);
							break;
					}
			}
		} , false);
	}


	public void updateUserStatus(String status , final DataInputListener<Boolean> dataInputListener) {
		String url = getAServerAddr()+"/func?task=setStatusMsg";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		url+="&statusMsg="+status.substring(0, status.length()>30 ? 30 : status.length());
		makeServerCall(url, new ServerNotifier() {
			@Override
			public void onServerResponse(MessageType messageType, ServerResponse response) {
				switch (messageType) {
					case OK:
						dataInputListener.onData(true);
						break;
					default:
						dataInputListener.onData(false);
						break;
				}
			}
		});
	}

	public void sendFeedBack(String feedback) {
		String url = getAServerAddr()+"/func?task=sendFeedback";
		url+="&encodedKey="+quizApp.getUserDeviceManager().getEncodedKey();
		HashMap<String,String > params = new HashMap<String, String>();
		params.put("feedback",feedback);
		makeServerPostCall(url, params, new ServerNotifier() {
					@Override
					public void onServerResponse(MessageType messageType, ServerResponse response) {
						switch (messageType) {
							case OK:
								break;
							default:
								break;
						}
					}
				});
	}
}

