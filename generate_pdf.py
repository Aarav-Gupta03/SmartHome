import os
import re
import subprocess
import base64

def get_base64_image(image_path):
    if not os.path.exists(image_path):
        return ""
    with open(image_path, "rb") as f:
        data = base64.b64encode(f.read()).decode("utf-8")
    ext = os.path.splitext(image_path)[1].replace(".", "").lower()
    return f"data:image/{ext};base64,{data}"

def create_html_and_pdf():
    with open("Review_3_Project_Functionalities.md", "r", encoding="utf-8") as f:
        md = f.read()

    # Build HTML with modern design
    html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Smart Home IoT Data Engine - Review 3 Documentation</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;600&display=swap');
  
  @page {
    size: A4;
    margin: 16mm 14mm 16mm 14mm;
    @bottom-right {
      content: counter(page);
    }
  }

  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    color: #1a202c;
    line-height: 1.55;
    background: #ffffff;
    margin: 0;
    padding: 0;
    font-size: 13.5px;
  }

  .header {
    text-align: center;
    border-bottom: 2px solid #e2e8f0;
    padding-bottom: 18px;
    margin-bottom: 24px;
  }

  h1 {
    font-size: 26px;
    color: #0f172a;
    margin: 0 0 6px 0;
    font-weight: 700;
    letter-spacing: -0.02em;
  }

  .subtitle {
    font-size: 15px;
    color: #3b82f6;
    margin: 0 0 10px 0;
    font-weight: 600;
  }

  .intro-text {
    font-size: 13px;
    color: #475569;
    max-width: 800px;
    margin: 0 auto;
    font-style: italic;
  }

  .part-header {
    background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
    color: #ffffff;
    padding: 10px 16px;
    border-radius: 8px;
    font-size: 16px;
    font-weight: 700;
    margin: 28px 0 16px 0;
    letter-spacing: -0.01em;
    page-break-after: avoid;
  }

  .feature-card {
    background: #ffffff;
    border: 1px solid #e2e8f0;
    border-radius: 10px;
    padding: 16px;
    margin-bottom: 22px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
    page-break-inside: avoid;
  }

  .feature-title {
    font-size: 15px;
    font-weight: 700;
    color: #0f172a;
    margin: 0 0 8px 0;
    display: flex;
    align-items: baseline;
    gap: 8px;
  }

  .feature-desc {
    color: #334155;
    margin: 0 0 12px 0;
    font-size: 13px;
    line-height: 1.5;
  }

  .code-block {
    background: #0f172a;
    color: #f8fafc;
    font-family: 'JetBrains Mono', Consolas, monospace;
    font-size: 11.5px;
    padding: 12px 14px;
    border-radius: 6px;
    overflow-x: auto;
    margin: 10px 0 14px 0;
    line-height: 1.45;
    border-left: 3px solid #3b82f6;
    white-space: pre-wrap;
  }

  .image-container {
    text-align: center;
    margin-top: 10px;
    background: #090a0f;
    padding: 10px;
    border-radius: 8px;
    border: 1px solid #1e293b;
  }

  .image-container img {
    max-width: 100%;
    max-height: 480px;
    height: auto;
    display: block;
    margin: 0 auto;
    border-radius: 4px;
  }

  .image-caption {
    font-size: 11.5px;
    color: #94a3b8;
    margin-top: 6px;
    font-style: italic;
  }

  .page-break {
    page-break-before: always;
  }
</style>
</head>
<body>

<div class="header">
  <h1>Smart Home IoT Data Engine</h1>
  <div class="subtitle">Project Functionalities & Review 3 Documentation</div>
  <p class="intro-text">
    This document outlines the core capabilities of the Smart Home IoT Data Engine for Review 3,
    divided into User-Defined Functionalities (Application Tier) and NoSQL Functionalities (Database Tier).
    All functionalities presented here are brand-new and distinct from Review 2.
  </p>
</div>
"""

    parts = re.split(r'###\s+Part\s+(\d+):\s+(.+)', md)
    i = 1
    while i < len(parts):
        part_num = parts[i]
        part_title = parts[i+1].strip()
        part_body = parts[i+2]
        i += 3

        if part_num == "2":
            html += '<div class="page-break"></div>'

        html += f'<div class="part-header">Part {part_num}: {part_title}</div>\n'

        feature_blocks = re.split(r'####\s+(\d+\..+)', part_body)
        f_idx = 1
        while f_idx < len(feature_blocks):
            feat_title = feature_blocks[f_idx].strip()
            feat_content = feature_blocks[f_idx+1]
            f_idx += 2

            code_match = re.search(r'```(?:java)?\n(.*?)```', feat_content, re.DOTALL)
            code_text = code_match.group(1).strip() if code_match else None

            img_match = re.search(r'!\[(.*?)\]\((.*?)\)', feat_content)
            img_caption = img_match.group(1) if img_match else None
            img_path = img_match.group(2) if img_match else None

            clean_desc = feat_content
            if code_match:
                clean_desc = clean_desc.replace(code_match.group(0), "")
            if img_match:
                clean_desc = clean_desc.replace(img_match.group(0), "")
            clean_desc = clean_desc.strip()

            html += '<div class="feature-card">\n'
            html += f'  <div class="feature-title">{feat_title}</div>\n'
            if clean_desc:
                html += f'  <p class="feature-desc">{clean_desc}</p>\n'

            if code_text:
                html += f'  <div class="code-block">{code_text}</div>\n'

            if img_path and os.path.exists(img_path):
                b64_src = get_base64_image(img_path)
                html += '  <div class="image-container">\n'
                html += f'    <img src="{b64_src}" alt="{img_caption or feat_title}" />\n'
                if img_caption:
                    html += f'    <div class="image-caption">Figure: {img_caption}</div>\n'
                html += '  </div>\n'

            html += '</div>\n'

    html += """
</body>
</html>
"""

    with open("Review_3_Project_Functionalities.html", "w", encoding="utf-8") as f:
        f.write(html)
    print("Saved Review_3_Project_Functionalities.html")

    # Run Edge headless to compile PDF
    edge_path = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
    if not os.path.exists(edge_path):
        edge_path = r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"

    abs_html = os.path.abspath("Review_3_Project_Functionalities.html")
    abs_pdf = os.path.abspath("Review_3_Project_Functionalities.pdf")

    cmd = [
        edge_path,
        "--headless",
        "--disable-gpu",
        "--run-all-compositor-stages-before-draw",
        f"--print-to-pdf={abs_pdf}",
        f"file:///{abs_html}"
    ]

    print("Generating PDF via Microsoft Edge...")
    res = subprocess.run(cmd, capture_output=True, text=True)
    if os.path.exists(abs_pdf):
        print(f"Successfully generated PDF: {abs_pdf} ({os.path.getsize(abs_pdf)} bytes)")
    else:
        print(f"Failed to generate PDF: {res.stderr}")

if __name__ == "__main__":
    create_html_and_pdf()
