import { defineStore } from 'pinia'

export const useSearchStore = defineStore('search', {
    state: () => {
        // 读取本地缓存初始化
        const cache = localStorage.getItem('anime_search_history')
        return {
            history: cache ? JSON.parse(cache) : [] as string[]
        }
    },
    actions: {
        // 添加搜索记录（去重、置顶、限制10条）
        addHistory(word: string) {
            const trimWord = word.trim()
            if (!trimWord) return
            this.history = this.history.filter((item: string) => item !== trimWord)
            this.history.unshift(trimWord)
            if (this.history.length > 10) {
                this.history = this.history.slice(0, 10)
            }
            localStorage.setItem('anime_search_history', JSON.stringify(this.history))
        },
        // 删除单条
        delItem(word: string) {
            this.history = this.history.filter((item: string) => item !== word)
            localStorage.setItem('anime_search_history', JSON.stringify(this.history))
        },
        // 清空全部
        clearAll() {
            this.history = []
            localStorage.removeItem('anime_search_history')
        }
    }
})