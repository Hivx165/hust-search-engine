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
  resultsContainer.innerHTML = "<p>Đang tải kết quả...</p>";
  const subdomain = subdomainFilter.value;

  const uiUrl = `/?keyword=${encodeURIComponent(keyword)}&subdomain=${encodeURIComponent(subdomain)}&page=${page}`;

  // Đẩy đường dẫn lên thanh địa chỉ mà không làm reload trang
  window.history.pushState({ keyword, subdomain, page }, "", uiUrl);

  const apiUrl = `/api/v1/search?keyword=${encodeURIComponent(keyword)}&subdomain=${encodeURIComponent(subdomain)}&page=${page}`;

  fetch(apiUrl)
    .then((response) => response.json())
    .then((data) => {
      renderResults(data);
      renderPagination(data);
    })
    .catch((error) => {
      resultsContainer.innerHTML =
        '<p style="color:red;">Lỗi kết nối đến máy chủ.</p>';
      console.error(error);
    });
}

//TODO: RENDER RESULTS
function renderResults(data) {
  searchStats.textContent = `Khoảng ${data.totalResults} kết quả (${data.processingTimeMs / 1000} giây)`;
  resultsContainer.innerHTML = "";

  if (data.results.length === 0) {
    resultsContainer.innerHTML = "<p>Không tìm thấy kết quả nào phù hợp.</p>";
    return;
  }

  data.results.forEach((doc) => {
    const div = document.createElement("div");
    div.className = "result-item";
    div.innerHTML = `
            <a href="${doc.url}" class="result-url" target="_blank">${doc.url}</a>
            <a href="${doc.url}" class="result-title" target="_blank">${doc.title}</a>
            
            <p class="result-snippet">${doc.snippet}</p>
        `;
    resultsContainer.innerHTML += div.outerHTML;
  });
  // <div style="font-size: 12px; color: #70757a; margin-bottom: 4px;">Điểm Lucene Score: ${doc.score.toFixed(4)}</div>
}

//TODO: PAGINATION
function renderPagination(data) {
  paginationContainer.innerHTML = "";
  if (data.totalPages <= 1) return; // Không cần phân trang nếu chỉ có 1 trang

  const { currentPage, totalPages } = data;

  const prevBtn = document.createElement("button");
  prevBtn.className = "page-btn";
  prevBtn.textContent = "Trước";
  prevBtn.disabled = currentPage === 1;
  prevBtn.onclick = () => fetchResults(currentKeyword, currentPage - 1);
  paginationContainer.appendChild(prevBtn);

  for (let i = 1; i <= totalPages; i++) {
    const pageBtn = document.createElement("button");
    pageBtn.className = `page-btn ${i === currentPage ? "active" : ""}`;
    pageBtn.textContent = i;
    pageBtn.onclick = () => fetchResults(currentKeyword, i);
    paginationContainer.appendChild(pageBtn);
  }

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
    div.innerHTML = `<span class="history-icon">🕒</span> ${keyword}`;

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

window.addEventListener("DOMContentLoaded", () => {
  // Đọc các tham số trên thanh địa chỉ URL
  const urlParams = new URLSearchParams(window.location.search);
  const keyword = urlParams.get("keyword");
  const subdomain = urlParams.get("subdomain") || "";
  const page = parseInt(urlParams.get("page")) || 1;

  // Nếu có từ khóa trên thanh URL -> Tự động điền vào ô input và gọi tìm kiếm
  if (keyword) {
    searchInput.value = keyword;
    subdomainFilter.value = subdomain;
    currentKeyword = keyword;

    // Gọi thẳng hàm fetch để bỏ qua bước lưu lịch sử khi mới load trang
    fetchResults(keyword, page);
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
