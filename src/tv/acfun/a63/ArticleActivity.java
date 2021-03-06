/*
 * Copyright (C) 2013 YROM.NET
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tv.acfun.a63;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import tv.acfun.a63.api.ArticleApi;
import tv.acfun.a63.api.Constants;
import tv.acfun.a63.api.entity.Article;
import tv.acfun.a63.api.entity.Article.SubContent;
import tv.acfun.a63.base.BaseWebViewActivity;
import tv.acfun.a63.db.DB;
import tv.acfun.a63.util.CustomUARequest;
import tv.acfun.a63.util.FileUtil;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings.LayoutAlgorithm;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.ShareActionProvider;
import com.alibaba.fastjson.JSON;
import com.android.volley.Cache.Entry;
import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import com.umeng.analytics.MobclickAgent;

/**
 * 文章页 结构： {@code <div id="title"> <h1 class="article-title"></h1>
 * <div id="info" class="article-info">
          <span class="article-publisher"><i class="icon-slash"></i></span>
          <span class="article-pubdate"></span>
          <span class="article-category"></span>
        </div>
    </div>
    <section id="content" class="article-body"></section>
    }
 * 
 * @author Yrom
 * 
 */
public class ArticleActivity extends BaseWebViewActivity implements Listener<Article>, ErrorListener {
    private static String ARTICLE_PATH;

    public static void start(Context context, int aid, String title) {
        Intent intent = new Intent(context, ArticleActivity.class);
        intent.putExtra("aid", aid);
        intent.putExtra("title", title);
        context.startActivity(intent);
    }

    private Request<?> request;
    private static Document _Doc;
    private List<String> imgUrls;
    private DownloadImageTask mDownloadTask;
    private String title;
    private boolean isDownloaded;
    private DB db;

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    protected void initView(Bundle savedInstanceState) {
        ARTICLE_PATH = AcApp.getExternalCacheDir("article").getAbsolutePath();
        
        if(Intent.ACTION_VIEW.equalsIgnoreCase(getIntent().getAction())
                &&getIntent().getData()!=null &&  getIntent().getData().getScheme().equals("ac")){
            // ac://ac000000
            aid = Integer.parseInt(getIntent().getDataString().substring(7));
            title = "ac"+aid;
        }else{
            aid = getIntent().getIntExtra("aid", 0);
            title = getIntent().getStringExtra("title");
        }
        if (aid == 0) {
            throw new IllegalArgumentException("没有 id");
        } else {
            getSupportActionBar().setTitle("ac" + aid);
            MobclickAgent.onEvent(this, "view_article","ac"+aid);
            db = new DB(this);
            isFaved = db.isFav(aid); 
            mWeb.getSettings().setAppCachePath(ARTICLE_PATH);
            mWeb.getSettings().setBlockNetworkImage(true);
            mWeb.addJavascriptInterface(new ACJSObject(), "AC");
            mWeb.setWebChromeClient(new WebChromeClient());
            mWeb.setWebViewClient(new WebViewClient() {

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    Pattern regex = Pattern.compile("/a/ac(\\d{5,})");
                    Matcher matcher = regex.matcher(url);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    if (matcher.find()) {
                        String acId = matcher.group(1);
                        try {
                            intent.setData(Uri.parse("ac://ac" + acId));
                            startActivity(intent);
                            return true;
                        } catch (Exception e) {
                            // nothing
                        }
                    }else if((matcher = Pattern.compile("/v/ac(\\d{5,})").matcher(url)).find()){
                        String acId = matcher.group(1);
                        try {
                            intent.setData(Uri.parse("av://ac" + acId));
                            startActivity(intent);
                            return true;
                        } catch (Exception e) {
                            // nothing
                        }
                        
                    }
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (imgUrls == null || imgUrls.isEmpty()
                            || url.startsWith("file:///android_asset") 
                            || AcApp.getViewMode() == Constants.MODE_NO_PIC) // 无图模式
                        return;
//                    Log.d(TAG, "on finished:" + url);
                    if (url.equals(Constants.URL_HOME) && imgUrls.size() > 0 && !isDownloaded) {
                        String[] arr = new String[imgUrls.size()];
                        mDownloadTask = new DownloadImageTask();
                        mDownloadTask.execute(imgUrls.toArray(arr));
                    }
                }

            });
            mWeb.getSettings().setSupportZoom(true);
            mWeb.getSettings().setBuiltInZoomControls(true);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
                mWeb.getSettings().setDisplayZoomControls(false);
//            mWeb.getSettings().setLayoutAlgorithm(LayoutAlgorithm.SINGLE_COLUMN);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if(AcApp.getViewMode() != Constants.MODE_COMMIC){
            getSupportMenuInflater().inflate(R.menu.article_options_menu, menu);
            MenuItem actionItem = menu.findItem(R.id.menu_item_share_action_provider_action_bar);
            ShareActionProvider actionProvider = (ShareActionProvider) actionItem.getActionProvider();
            actionProvider.setShareHistoryFileName(ShareActionProvider.DEFAULT_SHARE_HISTORY_FILE_NAME);
            actionProvider.setShareIntent(createShareIntent());
            if(isFaved){
                MenuItem fav = menu.findItem(R.id.menu_item_fav_action);
                fav.setIcon(R.drawable.rating_favorite_p);
                fav.setTitle("取消收藏");
            }
        }
        return super.onCreateOptionsMenu(menu);
    }

    private Intent createShareIntent() {
        String shareurl = title + "http://www.acfun.tv/a/ac" + aid;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "分享");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareurl);
        return shareIntent;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_item_comment:
            if(mArticle != null){
                CommentsActivity.start(ArticleActivity.this, mArticle.id);
            }
            return true;
        case R.id.menu_item_fav_action:
            if(isFaved){
                db.deleteFav(aid);
                AcApp.showToast("取消收藏");
                isFaved = false;
                item.setIcon(R.drawable.rating_favorite);
            }else{
                if(mArticle!= null){
                    db.addFav(mArticle);
                    isFaved = true;
                    item.setIcon(R.drawable.rating_favorite_p);
                    AcApp.showToast("收藏成功");
                }
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void initData() {
        super.initData();
        request = new ArticleRequest(aid, this, this);
        request.setTag(TAG);
        request.setShouldCache(true);
        Entry entry = AcApp.getGloableQueue().getCache().get(request.getCacheKey());
        if (entry != null && entry.data != null && entry.isExpired()) {
            try {
                String json = new String(entry.data, "utf-8");
                onResponse(Article.newArticle(JSON.parseObject(json)));
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        AcApp.addRequest(request);

    }

    private String buildTitle(Article article) {
        StringBuilder builder = new StringBuilder();
        builder.append("<h1 class=\"article-title\">")
                .append(article.title)
                .append("</h1>")
                .append("<div id=\"info\" class=\"article-info\">")
                .append("<span class=\"article-publisher\"><img id=\"icon\" src=\"file:///android_asset/wen2.png\" width='18px' height='18px'/> ")
                .append("<a href=\"http://www.acfun.tv/member/user.aspx?uid=")
                .append(article.poster.id).append("\" >")
                .append(article.poster.name)
                .append("</a>").append("</span>")
                .append("<span class=\"article-pubdate\">")
                .append(AcApp.getPubDate(article.postTime))
                .append("发布于</span>")
                .append("<span class=\"article-category\">")
                .append(article.channelName)
                .append("</span>")
                .append("</div>");

        return builder.toString();
    }

    private static final String TAG = "Article";
    private Article mArticle;
    static class ArticleRequest extends CustomUARequest<Article> {

        public ArticleRequest(int aid, Listener<Article> listener, ErrorListener errListener) {
            super(ArticleApi.getContentUrl(aid), Article.class, listener, errListener);
        }

        @Override
        protected Response<Article> parseNetworkResponse(NetworkResponse response) {
            try {
                String json = new String(response.data,
                        HttpHeaderParser.parseCharset(response.headers));
                return Response.success(Article.newArticle(JSON.parseObject(json)),
                        HttpHeaderParser.parseCacheHeaders(response));
            } catch (Exception e) {
                Log.e(TAG, "parse article error", e);
                return Response.error(new ParseError(e));
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AcApp.cancelAllRequest(TAG);
        if (mDownloadTask != null && !isDownloaded) {
            mDownloadTask.cancel(false);
        }
    }

    @Override
    public void onResponse(Article response) {
        mArticle = response;
        imgUrls = response.imgUrls;
        if(AcApp.getViewMode() == Constants.MODE_COMMIC && imgUrls != null && !imgUrls.isEmpty()){
            ImagePagerActivity.startNetworkImage(this, (ArrayList<String>) imgUrls,0,aid,title);
            finish();
        }else
            new BuildDocTask().execute(mArticle);

    }
    @Override
    public void onErrorResponse(VolleyError error) {
        setSupportProgressBarIndeterminateVisibility(false);
        showErrorDialog();
    }

    List<File> imageCaches;
    private int aid;
    private boolean isFaved;
    
    private class BuildDocTask extends AsyncTask<Article, Void, Boolean> {
        boolean hasUseMap;

        @Override
        protected void onPreExecute() {
        }

        @Override
        protected Boolean doInBackground(Article... params) {
            try {
                if(_Doc == null){
                    InputStream in = getAssets().open("article.html");
                    _Doc = Jsoup.parse(in, "utf-8", "");
                }
                initCaches();
                Element title = _Doc.getElementById("title");
                title.html(buildTitle(params[0]));
                Element content = _Doc.getElementById("content");

                ArrayList<SubContent> contents = params[0].contents;

                for (int i = 0; i < contents.size(); i++) {
                    SubContent sub = contents.get(i);
                    handleSubContent(content, sub, params[0]);
                }

            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }

        private void handleSubContent(Element content, SubContent sub, Article article) {
            content.empty();
            if (!article.title.equals(sub.subTitle)) {
                content.append("<h2 class=\"article-subtitle\">" + sub.subTitle + "</h2><hr>");
            }
            content.append(sub.content);
            Elements imgs = content.select("img");
            if (imgs.hasAttr("usemap")) {
                hasUseMap = true;
            }
            for (int imgIndex = 0; imgIndex < imgs.size(); imgIndex++) {
                Element img = imgs.get(imgIndex);
                String src = img.attr("src").trim();
                if (src.startsWith("file"))
                    continue;
                if (!src.startsWith("http")) {
                    src = "http://www.acfun.tv" + src;
                }
//                File cache = new File(AcApp.getExternalCacheDir(AcApp.IMAGE + "/" + mArticle.id),
//                        FileUtil.getHashName(src));
                File cache = FileUtil.generateImageCacheFile(src);
                imageCaches.add(cache);
                imgUrls.add(src);
                img.attr("org", src);
                String localUri = FileUtil.getLocalFileUri(cache).toString();
                if (cache.exists() && cache.canRead() && cache.length() > FileUtil._1KB)
                    // set cache
                    img.attr("src", localUri);
                else if (AcApp.getViewMode() != Constants.MODE_NO_PIC)
                    img.attr("src", "file:///android_asset/loading.gif");
                else {
                    // 无图模式
                    // TODO 点击后加载图片
                    img.after("<p >[图片]</p>");
                    img.remove();
                    continue;
                }
                img.attr("loc", localUri);
                // 去掉 style
                img.removeAttr("style");
                    // 给 img 标签加上点击事件
                if (!hasUseMap){
                    addClick(img, src);
                    img.removeAttr("width");
                    img.removeAttr("height");
                }
            }
        }

        private void initCaches() {
            if (imgUrls != null)
                imgUrls.clear();
            else
                imgUrls = new ArrayList<String>();
            if (imageCaches != null)
                imageCaches.clear();
            else
                imageCaches = new ArrayList<File>();
        }

        private void addClick(Element img, String src) {
            try {
                if ("icon".equals(img.attr("class")) || Integer.parseInt(img.attr("width")) < 100
                        || Integer.parseInt(img.attr("height")) < 100) {
                    return;
                }
            } catch (Exception e) {
            }
            if (src.contains("emotion/images/"))
                return;
            // 过滤掉图片的url跳转
            if (img.parent() != null && img.parent().tagName().equalsIgnoreCase("a")) {
                img.parent().attr("href", "javascript:window.AC.viewImage('" + src + "');");
            } else {
                img.attr("onclick", "javascript:window.AC.viewImage('" + src + "');");
            }

        }

        @Override
        protected void onPostExecute(Boolean result) {
            setSupportProgressBarIndeterminateVisibility(false);
            if (result) {
                mWeb.loadDataWithBaseURL("http://www.acfun.tv/", _Doc.html(), "text/html", "UTF-8",
                        null);
                if (hasUseMap)
                    mWeb.getSettings().setLayoutAlgorithm(LayoutAlgorithm.NARROW_COLUMNS);
                else
                    mWeb.getSettings().setLayoutAlgorithm(LayoutAlgorithm.SINGLE_COLUMN);

            }
        }

    };

    /**
     * 异步下载图片到缓存目录
     * 
     * @author Yrom
     * 
     */
    private class DownloadImageTask extends AsyncTask<String, Integer, Void> {

        int timeoutMs = 3000;
        int tryTimes = 3;

        @Override
        protected Void doInBackground(String... params) {
            for (int index = 0; index < params.length; index++) {
                String url = params[index];
                if (isCancelled()) {
                    // cancel task on activity destory
                    Log.w(TAG, String.format("break download task,index=%d,src=%s", index,url));
                    break;
                }
                File cache = imageCaches.get(imgUrls.indexOf(url));
                Log.i(TAG, "cache file = "+cache.getAbsolutePath());
                if (cache.exists() && cache.canRead()) {
                    publishProgress(index);
                    continue;
                } else {
                    cache.getParentFile().mkdirs();
                }
                File temp = new File(cache.getAbsolutePath()+".tmp");
                
                InputStream in = null;
                OutputStream out = null;

                try {
                    URL parsedUrl = new URL(url);
                    retry: for (int i = 0; i < tryTimes && !isCancelled(); i++) {
                        
                        HttpURLConnection connection = (HttpURLConnection) parsedUrl
                                .openConnection();
                        connection.setConnectTimeout(timeoutMs + i * 1500);
                        connection.setReadTimeout(timeoutMs * (2 + i));
                        connection.setUseCaches(false);
                        connection.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/29.0.1547.66 Safari/537.36");
                        if(temp.exists()){
                            connection.addRequestProperty("Range", "bytes="+temp.length()+"-");
                            out = new FileOutputStream(temp,true);
                        }else
                            out = new FileOutputStream(temp);
                        try {
                            int responseCode = connection.getResponseCode();
                            if (responseCode == 200 || responseCode == 206) {
                                in = connection.getInputStream();
                                FileUtil.copyStream(in, out);
                                cache.delete();
                                if(!temp.renameTo(cache)){
                                    Log.w(TAG, "重命名失败"+temp.getName());
                                }
                                publishProgress(index);
                                break retry;
                            }
                        } catch (SocketTimeoutException e) {
                            Log.w(TAG, "retry", e);
                        }
                    }
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (in != null)
                            in.close();
                    } catch (IOException e) {
                    }
                    try {
                        if (out != null)
                            out.close();
                    } catch (IOException e) {
                    }
                }

            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (imgUrls != null) {
                String url = imgUrls.get(values[0]);
                if (url == null)
                    return;
                Log.i(TAG, url + " cached");
                mWeb.loadUrl("javascript:(function(){"
                        + "var images = document.getElementsByTagName(\"img\"); "
                        + "images["+ values[0] + "].src = images[" + values[0] + "].getAttribute(\"loc\");"
                        + "})()");
            }
        }

        @Override
        protected void onPostExecute(Void result) {
            // 确保所有图片都顺利的显示出来
            isDownloaded = true;
            mWeb.loadUrl("javascript:(function(){"
                    + "var images = document.getElementsByTagName(\"img\"); "
                    + "for(var i=0;i<images.length;i++){"
                    +   "var imgSrc = images[i].getAttribute(\"loc\"); "
                    +   "images[i].setAttribute(\"src\",imgSrc);"
                    + "}"
                    + "})()");
        }

    }

    class ACJSObject {
        @android.webkit.JavascriptInterface
        public void viewcomment() {
            CommentsActivity.start(ArticleActivity.this, mArticle.id);
        }

        @android.webkit.JavascriptInterface
        public void viewImage(String url) {
            ImagePagerActivity.startCacheImage(ArticleActivity.this, (ArrayList<File>) imageCaches, imgUrls.indexOf(url),aid,title);
        }
    }

}
