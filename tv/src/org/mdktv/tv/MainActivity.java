package org.mdktv.tv;

import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private WebView web;
    private LinearLayout home;
    private EditText address;
    private TextView status;
    private final String focusScript = "(function(){if(document.getElementById('mdktv-focus'))return;var s=document.createElement('style');s.id='mdktv-focus';s.textContent=':focus{outline:4px solid #54e6ca!important;outline-offset:3px!important}';document.head.appendChild(s);document.querySelectorAll('button,a,input,select,textarea,[role=button]').forEach(function(e){if(!e.disabled)e.tabIndex=0});document.addEventListener('keydown',function(e){var ds={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]},d=ds[e.key];if(!d||/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName))return;var els=Array.from(document.querySelectorAll('button,a,input,select,textarea,[tabindex]')).filter(function(x){var r=x.getBoundingClientRect();return !x.disabled&&r.width>0&&r.height>0}),a=document.activeElement,r=a.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2,best=null,score=Infinity;els.forEach(function(x){if(x===a)return;var q=x.getBoundingClientRect(),dx=q.left+q.width/2-cx,dy=q.top+q.height/2-cy,along=dx*d[0]+dy*d[1],across=Math.abs(dx*d[1]-dy*d[0]);if(along>1&&along+across*3<score){best=x;score=along+across*3}});if(!els.includes(a))best=els[0];if(best){e.preventDefault();best.focus();best.scrollIntoView({block:'nearest'})}})})()";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showHome();
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(Color.WHITE); return v;
    }
    private void button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); home.addView(b);
    }
    private void showHome() {
        if (web != null) { web.destroy(); web = null; }
        home = new LinearLayout(this); home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(48),dp(20),dp(48),dp(20)); home.setBackgroundColor(Color.rgb(18,28,46));
        home.addView(text("MDKTV · 电视版 0.1",28));
        home.addView(text("连接已部署的 K歌 / fnOS 服务，使用自己的媒体来源",16));
        address = new EditText(this); address.setSingleLine(true); address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.LTGRAY); address.setHint("http://NAS地址:端口/ 或完整应用地址");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(getPreferences(0).getString("server", "")); home.addView(address);
        button("连接点歌服务", () -> connect(address.getText().toString().trim()));
        button("使用说明 / 开源许可", () -> new AlertDialog.Builder(this).setTitle("初始 TV 客户端")
            .setMessage("先部署仓库中的 NAS 服务，再输入电视可访问的服务地址（保留应用路径）。\n方向键移动焦点，确认键选择；返回键返回网页，菜单键回到连接页。\n首次使用需在服务端连接媒体来源。HTTP 仅适合可信局域网，远程访问请使用 HTTPS。\n此版本使用系统 WebView，未集成原生麦克风、评分及音效；播放兼容性取决于电视系统。\n\n基于麦动（Maidong KTV），原作者：杨家三郎；K歌由 FXD 维护。完全免费、公开源码、禁止商用。完整许可见随包源码 tv/assets/LICENSE 和 THIRD-PARTY-COMPONENTS.md。")
            .setPositiveButton("确定", null).show());
        status = text("遥控器：方向键移动 · 确认选择 · 菜单键设置",14); home.addView(status);
        setContentView(home); address.requestFocus();
    }
    private void connect(String url) {
        Uri uri = Uri.parse(url);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null) {
            status.setText("请输入有效的 http:// 或 https:// 服务地址，请勿在地址中填写账号密码。"); return;
        }
        getPreferences(0).edit().putString("server", url).apply();
        web = new WebView(this); web.setBackgroundColor(Color.rgb(18,28,46));
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        web.setWebChromeClient(new WebChromeClient() {
            private View full;
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if(full!=null){callback.onCustomViewHidden();return;} full=view;
                addContentView(view,new android.view.ViewGroup.LayoutParams(-1,-1));
            }
            @Override public void onHideCustomView(){if(full!=null){((android.view.ViewGroup)full.getParent()).removeView(full);full=null;}}
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme=r.getUrl().getScheme(); return !("http".equals(scheme)||"https".equals(scheme));
            }
            @Override public void onPageFinished(WebView v,String page){v.evaluateJavascript(focusScript,null);}
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError error) {
                if(r.isForMainFrame()) new AlertDialog.Builder(MainActivity.this).setTitle("连接失败")
                    .setMessage("请确认 NAS 服务已启动、地址和端口正确，电视与 NAS 网络互通。")
                    .setPositiveButton("设置地址",(d,w)->showHome()).setNegativeButton("重试",(d,w)->v.reload()).show();
            }
        });
        setContentView(web); web.requestFocus(); web.loadUrl(url);
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if(key==KeyEvent.KEYCODE_MENU){showHome();return true;} return super.onKeyDown(key,event);
    }
    @Override public void onBackPressed() {
        if(web!=null){if(web.canGoBack())web.goBack();else showHome();}else super.onBackPressed();
    }
    @Override protected void onPause(){if(web!=null)web.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
    @Override protected void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}
}
