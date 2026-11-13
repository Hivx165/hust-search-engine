const searchInput = document.getElementById("searchInput");
const searchBtn = document.getElementById("searchBtn");
const resultsContainer = document.getElementById("resultsContainer");
const searchStats = document.getElementById("searchStats");
const paginationContainer = document.getElementById("paginationContainer");
const searchHistoryDropdown = document.getElementById("searchHistory");
const HISTORY_KEY = "hust_search_history"; // Tên biến lưu trong trình duyệt
const searchBoxContainer = document.querySelector(".search-box-container");
const subdomainFilter = document.getElementById("subdomainFilter");
// Trạng thái toàn cục
let currentKeyword = "";

//TODO: EVENT LISTENERS
searchBtn.addEventListener("click", () => triggerSearch());
searchInput.addEventListener("keypress", (e) => {
  if (e.key === "Enter") triggerSearch();
});

function triggerSearch() {
  const keyword = searchInput.value.trim();
  if (!keyword) return;
  currentKeyword = keyword;

  saveHistory(keyword);

  searchHistoryDropdown.classList.remove("show");
  searchBoxContainer.classList.remove("active");
  fetchResults(keyword, 1);
}

//TODO: CALL API Spring Boot
function fetchResults(keyword, page) {
  // SỬA Ở ĐÂY: Gọi hiệu ứng nhấp nháy Skeleton thay vì in chữ
  showLoadingSkeleton();

  const subdomain = subdomainFilter.value;
  const uiUrl = `/?keyword=${encodeURIComponent(keyword)}&subdomain=${encodeURIComponent(subdomain)}&page=${page}`;

  window.history.pushState({ keyword, subdomain, page }, "", uiUrl);

  const apiUrl = `/api/search?keyword=${encodeURIComponent(keyword)}&subdomain=${encodeURIComponent(subdomain)}&page=${page}`;

  fetch(apiUrl)
    .then((response) => response.json())
    .then((data) => {
      renderResults(data);
      renderPagination(data);
    })
    .catch((error) => {
      resultsContainer.innerHTML =
        '<p style="color:red; padding: 20px;">Lỗi kết nối đến máy chủ.</p>';
      console.error(error);
    });
}

//TODO: RENDER RESULTS
function renderResults(data) {
  searchStats.textContent = `Khoảng ${data.totalResults} kết quả (${(data.processingTimeMs / 1000).toFixed(2)} giây)`;
  resultsContainer.innerHTML = "";

  if (!data.results || data.results.length === 0) {
    resultsContainer.innerHTML =
      '<p style="color: #5f6368; padding: 20px;">Không tìm thấy kết quả nào phù hợp.</p>';
    return;
  }

  let htmlContent = "";
  data.results.forEach((doc) => {
    const breadcrumbText = formatBreadcrumb(doc.url);
    const faviconSrc = `https://www.google.com/s2/favicons?domain=${doc.url}&sz=32`;

    htmlContent += `
      <div class="result-item">
          <a href="${doc.url}" target="_blank">
              <div class="breadcrumb-url">
                  <img class="favicon" src="${faviconSrc}" alt="icon" onerror="this.style.display='none'" />
                  <span>${breadcrumbText}</span>
              </div>
              <h3 class="result-title">${doc.title}</h3>
          </a>
          <p class="result-snippet">${doc.snippet}</p>
      </div>
    `;
  });

  resultsContainer.innerHTML = htmlContent;
}
//TODO: PAGINATION
function renderPagination(data) {
  paginationContainer.innerHTML = "";
  if (data.totalPages <= 1) return;

  const { currentPage, totalPages } = data;

  // Cấu hình số lượng nút tối đa muốn hiển thị (nên để số lẻ như 5 hoặc 7)
  const maxVisibleButtons = 7;

  // Tính toán điểm bắt đầu và kết thúc của dải nút
  let startPage = Math.max(1, currentPage - Math.floor(maxVisibleButtons / 2));
  let endPage = startPage + maxVisibleButtons - 1;

  // Điều chỉnh lại nếu dải nút vượt quá tổng số trang
  if (endPage > totalPages) {
    endPage = totalPages;
    startPage = Math.max(1, endPage - maxVisibleButtons + 1);
  }

  // --- NÚT TRƯỚC ---
  const prevBtn = document.createElement("button");
  prevBtn.className = "page-btn";
  prevBtn.textContent = "Trước";
  prevBtn.disabled = currentPage === 1;
  prevBtn.onclick = () => fetchResults(currentKeyword, currentPage - 1);
  paginationContainer.appendChild(prevBtn);

  // --- HIỂN THỊ TRANG ĐẦU VÀ DẤU ... NẾU CẦN ---
  if (startPage > 1) {
    const firstBtn = document.createElement("button");
    firstBtn.className = "page-btn";
    firstBtn.textContent = "1";
    firstBtn.onclick = () => fetchResults(currentKeyword, 1);
    paginationContainer.appendChild(firstBtn);

    if (startPage > 2) {
      const dots = document.createElement("span");
      dots.className = "page-dots";
      dots.textContent = "...";
      paginationContainer.appendChild(dots);
    }
  }

  // --- RENDER CÁC TRANG Ở GIỮA ---
  for (let i = startPage; i <= endPage; i++) {
    const pageBtn = document.createElement("button");
    pageBtn.className = `page-btn ${i === currentPage ? "active" : ""}`;
    pageBtn.textContent = i;
    pageBtn.onclick = () => fetchResults(currentKeyword, i);
    paginationContainer.appendChild(pageBtn);
  }

  // --- HIỂN THỊ TRANG CUỐI VÀ DẤU ... NẾU CẦN ---
  if (endPage < totalPages) {
    if (endPage < totalPages - 1) {
      const dots = document.createElement("span");
      dots.className = "page-dots";
      dots.textContent = "...";
      paginationContainer.appendChild(dots);
    }

    const lastBtn = document.createElement("button");
    lastBtn.className = "page-btn";
    lastBtn.textContent = totalPages;
    lastBtn.onclick = () => fetchResults(currentKeyword, totalPages);
    paginationContainer.appendChild(lastBtn);
  }

  // --- NÚT TIẾP ---
  const nextBtn = document.createElement("button");
  nextBtn.className = "page-btn";
  nextBtn.textContent = "Tiếp";
  nextBtn.disabled = currentPage === totalPages;
  nextBtn.onclick = () => fetchResults(currentKeyword, currentPage + 1);
  paginationContainer.appendChild(nextBtn);
}

function saveHistory(keyword) {
  let history = JSON.parse(localStorage.getItem(HISTORY_KEY)) || [];
  history = history.filter((item) => item !== keyword);
  history.unshift(keyword);
  if (history.length > 5) history.pop();
  localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
}

function showHistory() {
  const history = JSON.parse(localStorage.getItem(HISTORY_KEY)) || [];
  if (history.length === 0) return;

  searchHistoryDropdown.innerHTML = "";
  history.forEach((keyword) => {
    const div = document.createElement("div");
    div.className = "history-item";
    div.innerHTML = `
      <span class="history-icon">
        <svg focusable="false" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
          <path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z"></path>
        </svg>
      </span>
      <span class="history-text">${keyword}</span>
    `;

    div.onmousedown = () => {
      searchInput.value = keyword;
      // Xóa class khi click chọn
      searchHistoryDropdown.classList.remove("show");
      searchBoxContainer.classList.remove("active");
      triggerSearch();
    };
    searchHistoryDropdown.appendChild(div);
  });

  // Thêm class khi mở
  searchHistoryDropdown.classList.add("show");
  searchBoxContainer.classList.add("active");
}

searchInput.addEventListener("focus", showHistory);

document.addEventListener("click", (e) => {
  if (!e.target.closest(".search-box-container")) {
    // Xóa class khi click ra ngoài
    searchHistoryDropdown.classList.remove("show");
    searchBoxContainer.classList.remove("active");
  }
});

//TODO: BREADCRUMB
function formatBreadcrumb(urlStr) {
  try {
    const urlObj = new URL(urlStr);
    const hostname = urlObj.hostname.replace("www.", "");
    const paths = urlObj.pathname
      .split("/")
      .filter((p) => p.length > 0)
      .slice(0, 2);

    let breadcrumb = hostname;
    if (paths.length > 0) {
      breadcrumb += " &rsaquo; " + paths.join(" &rsaquo; ");
    }
    return breadcrumb;
  } catch (e) {
    return urlStr; // Trả về URL gốc nếu bị lỗi parse
  }
}

//TODO: LOADING STATE
function showLoadingSkeleton() {
  // Ẩn thống kê và phân trang cũ
  searchStats.innerHTML = "";
  paginationContainer.innerHTML = "";

  let skeletonHTML = "";
  // Vẽ 5 bộ khung giả lập để người dùng xem trong lúc chờ
  for (let i = 0; i < 5; i++) {
    skeletonHTML += `
        <div class="skeleton-item">
            <div class="skeleton skeleton-url"></div>
            <div class="skeleton skeleton-title"></div>
            <div class="skeleton skeleton-text"></div>
            <div class="skeleton skeleton-text short"></div>
        </div>`;
  }
  resultsContainer.innerHTML = skeletonHTML;
}

//TODO: LOAD SUBDOMAINS TỪ BACKEND
async function loadSubdomains(initialSubdomain = "") {
  const subdomainSelect = document.getElementById("subdomainFilter");

  try {
    const response = await fetch("/api/subdomains");
    const data = await response.json();

    subdomainSelect.innerHTML = ""; // Xóa dữ liệu cũ

    data.forEach((item) => {
      const option = document.createElement("option");
      option.value = item.value;
      option.textContent = item.label;
      subdomainSelect.appendChild(option);
    });

    // Nếu trên URL có sẵn subdomain (do người dùng tải lại trang hoặc copy link), thì tự động chọn đúng tùy chọn đó
    if (initialSubdomain) {
      subdomainSelect.value = initialSubdomain;
    }
  } catch (error) {
    console.error("Lỗi khi tải danh sách subdomain:", error);
    // Fallback dự phòng nếu server lỗi
    subdomainSelect.innerHTML = '<option value="">Tất cả các Trường</option>';
  }
}

window.addEventListener("DOMContentLoaded", async () => {
  // Đọc các tham số trên thanh địa chỉ URL
  const urlParams = new URLSearchParams(window.location.search);
  const keyword = urlParams.get("keyword");
  const subdomain = urlParams.get("subdomain") || "";
  const page = parseInt(urlParams.get("page")) || 1;

  await loadSubdomains(subdomain);

  // Nếu có từ khóa trên thanh URL -> Tự động điền vào ô input và gọi tìm kiếm
  if (keyword) {
    searchInput.value = keyword;
    subdomainFilter.value = subdomain;
    currentKeyword = keyword;

    // Gọi thẳng hàm fetch để bỏ qua bước lưu lịch sử khi mới load trang
    fetchResults(keyword, page);
  }
});

subdomainFilter.addEventListener("change", () => {
  if (currentKeyword) {
    fetchResults(currentKeyword, 1);
  }
});

window.addEventListener("popstate", (event) => {
  if (event.state) {
    const { keyword, subdomain, page } = event.state;
    searchInput.value = keyword;
    subdomainFilter.value = subdomain;
    currentKeyword = keyword;

    // Gọi lại hàm để render dữ liệu cũ mà không làm reload trang
    fetchResults(keyword, page);
  } else {
    // Nếu quay về trang chủ (không có tham số)
    resultsContainer.innerHTML = "";
    searchStats.textContent = "";
    paginationContainer.innerHTML = "";
    searchInput.value = "";
  }
});
