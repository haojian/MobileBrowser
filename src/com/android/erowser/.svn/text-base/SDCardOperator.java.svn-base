package com.android.erowser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class SDCardOperator {
	static void writeToSDcardFile(String fileName, String dir, String content){
		String sDStateString = android.os.Environment.getExternalStorageState();
		File myFile = null;
		if (sDStateString.equals(android.os.Environment.MEDIA_MOUNTED)) {
			try{
				File SDFile = android.os.Environment.getExternalStorageDirectory();
				File destDir = new File(SDFile.getAbsolutePath() + dir);
				if (!destDir.exists())
					destDir.mkdir();
				myFile = new File(destDir + File.separator + fileName);
				if (!myFile.exists()) {
					myFile.createNewFile();
					}
				FileOutputStream outputStream = new FileOutputStream(myFile, true);
				outputStream.write(content.getBytes());
				outputStream.close();
				}
		catch(Exception e){
			e.printStackTrace();
			}
		}
	}
	
	static String readFromSDcardFile(String fileName, String dir){
		String res = null;
		File SDFile = android.os.Environment.getExternalStorageDirectory();
		File myFile = new File(SDFile.getAbsolutePath() + dir + File.separator + fileName);
		if(myFile.exists())
		{
			try{
				FileInputStream inputStream = new FileInputStream(myFile);
				byte[] buffer = new byte[1024];
				inputStream.read(buffer);
				inputStream.close();
				res=new String(buffer);
			}catch (Exception e){
				
			}
		}
		return res;
	}
}
