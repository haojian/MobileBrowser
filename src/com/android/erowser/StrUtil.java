package com.android.erowser;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;

public class StrUtil {
	private static final String startTag = "<li class=\"g\"><h3 class=\"r\"><a href=";
	private static final String endTag = "Similar</a></span></div></div></li>";
	static String Encode(String before) {
		String after = null;
		try {
			after = URLEncoder.encode(before, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return after;
	}

	static String decode(String original){
		String result = null;
		try{
			result = URLDecoder.decode(original, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return result;
	}
	
	static String extractUrl(String str){
		String res = null;
		int index_start = str.indexOf("&url=") + 5;
		int index_end = 0;
		if(index_start > 0 && index_start< str.length())
			index_end = str.indexOf("&", index_start);
		res = str.substring(index_start, index_end);
		res = StrUtil.decode(res);
		return res;
	}
	
	static String extractTime(String str){
		String res = null;
		int index_start = str.indexOf("&time=") + 6;
		int index_end = 0;
		if(index_start > 0 && index_start< str.length())
			index_end = str.indexOf("&", index_start);
		res = str.substring(index_start, index_end);
		return res;
	}
	
	static String extractUID(String str){
		String res = null;
		int index_start = str.indexOf("&uid=") + 5;
		int index_end = -1;
		if(index_start > 0 && index_start< str.length())
			index_end = str.indexOf("&", index_start);
		if(index_end > index_start)
			res = str.substring(index_start, index_end);
		return res;
	}
	
	static String convertToHtml(String str){
		String res = null;
		res = "<html><body>Hello <b>World</b> !! </body></html>";
		return res;
	}
	
	static ArrayList<String> extractGoogleResultAbstracts(String str){
		ArrayList<String> res = new ArrayList<String>();
		int index_start = 0, index_end = 0;

		while((index_start = str.indexOf(startTag, index_start)) >0 )
		{
			if((index_end = str.indexOf(endTag, index_start)) > 0)
			{
				String tmpAbstract = str.substring(index_start, index_end + endTag.length());
				if(tmpAbstract != null && tmpAbstract.length() != 0)
					res.add(tmpAbstract);
				index_start = index_end + endTag.length();
			}
			else
				break;
		}
		return res;
	}
	
	static boolean bIsResultContent(String str){
		if(str.contains("<ol><li class=\"g\">"))
			return true;
		return false;
	}
	
	static String extractURLfromAbstract(String str){
		String res = null;
		int index_start = 0, index_end = 0;
		if(str.contains(startTag) && str.contains(endTag))
		{
			index_start = str.indexOf("<cite>") + 6;
			index_end = str.indexOf("</cite>");
			if(index_start >= 0 && index_end>=0 && index_end>index_start)
			{
				res = str.substring(index_start, index_end);
				res = android.text.Html.fromHtml(res).toString();
				//res = res.replaceAll("\\&[a-zA-Z]{1,10};", "").replaceAll("<[^>]*>", "").replaceAll("[(/>)<]", "");
			}
		}
		return res;
	}
	
	static boolean bIsGoogleResultURL(String str){
		if(str.contains("http://www.google.com/search?"))
			return true;
		return false;
	}
	
	static String getUnviewedGoogleResultURL(String str){
		if(!str.contains("&start="))
			return str + "&start=100";
		else{
			int index_start = str.indexOf("&start=")+7;
			int index_end = str.indexOf("&", index_start);
			String startTag = str.substring(index_start,index_end);
			int start_index = Integer.parseInt(startTag);
			String newTag = String.valueOf(start_index+100);
			if(startTag != null && startTag != "")
				str = str.replace("&start=" + startTag + "&", "&start=" + newTag + "&");
			return str;
		}
			
	}
}
